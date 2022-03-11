package org.folio.shared.index.storage;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.tlib.postgres.TenantPgPool;
import org.folio.tlib.util.TenantUtil;

// Define a constant instead of duplicating this literal
@java.lang.SuppressWarnings({"squid:S1192"})
public class Storage {
  private static final Logger log = LogManager.getLogger(Storage.class);

  private static final String CREATE_IF_NO_EXISTS = "CREATE TABLE IF NOT EXISTS ";
  TenantPgPool pool;
  String bibRecordTable;
  String matchKeyConfigTable;
  String matchKeyValueTable;
  String itemView;

  /**
   * Create storage service for tenant.
   * @param vertx Vert.x hande
   * @param tenant tenant
   */
  public Storage(Vertx vertx, String tenant) {
    this.pool = TenantPgPool.pool(vertx, tenant);
    this.bibRecordTable = pool.getSchema() + ".bib_record";
    this.matchKeyConfigTable = pool.getSchema() + ".match_key_config";
    this.matchKeyValueTable = pool.getSchema() + ".match_key_value";
    this.itemView = pool.getSchema() + ".item_view";
  }

  public Storage(RoutingContext routingContext) {
    this(routingContext.vertx(), TenantUtil.tenant(routingContext));
  }

  /**
   * Prepares storage with tables, etc.
   * @return async result.
   */
  public Future<Void> init() {
    return pool.execute(List.of(
            "SET search_path TO " + pool.getSchema(),
            CREATE_IF_NO_EXISTS + bibRecordTable
                + "(id uuid NOT NULL PRIMARY KEY,"
                + "local_identifier VARCHAR NOT NULL,"
                + "library_id uuid NOT NULL,"
                + "source JSONB NOT NULL,"
                + "inventory JSONB"
                + ")",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_local_id ON " + bibRecordTable
                + " (local_identifier, library_id)",
            "CREATE OR REPLACE VIEW " + itemView
                + " AS SELECT id, local_identifier, library_id,"
                + " jsonb_array_elements("
                + " (jsonb_array_elements((inventory->>'holdingsRecords')::JSONB)->>'items')::JSONB"
                + ") item FROM " + bibRecordTable,
            CREATE_IF_NO_EXISTS + matchKeyConfigTable
                + "(id VARCHAR NOT NULL PRIMARY KEY,"
                + " method VARCHAR, "
                + " params JSONB)",
            CREATE_IF_NO_EXISTS + matchKeyValueTable
                + "(bib_record_id uuid NOT NULL,"
                + " match_key_config_id uuid NOT NULL,"
                + " match_value VARCHAR NOT NULL,"
                + " CONSTRAINT match_key_value_fk_bib_record FOREIGN KEY "
                + "                (bib_record_id) REFERENCES " + bibRecordTable + ")",
            "CREATE UNIQUE INDEX IF NOT EXISTS match_key_value_idx ON " + matchKeyValueTable
                + " (match_key_config_id, match_value, bib_record_id)",
            "CREATE INDEX IF NOT EXISTS match_key_value_bib_id_idX ON " + matchKeyValueTable
                + " (bib_record_id)"
        )
    ).mapEmpty();
  }

  Future<Void> upsertBibRecord(
      SqlConnection conn,
      String localIdentifier,
      UUID libraryId,
      JsonObject source,
      JsonObject inventory) {

    return conn.preparedQuery(
        "INSERT INTO " + bibRecordTable
            + " (id, local_identifier, library_id, source, inventory)"
            + " VALUES ($1, $2, $3, $4, $5)"
            + " ON CONFLICT (local_identifier, library_id) DO UPDATE "
            + " SET source = $4, "
            + "     inventory = $5").execute(
        Tuple.of(UUID.randomUUID(), localIdentifier, libraryId, source, inventory)
    ).mapEmpty();
  }

  Future<Void> upsertSharedRecord(SqlConnection conn, UUID sourceId, JsonObject sharedRecord) {
    final String localIdentifier = sharedRecord.getString("localId");
    final JsonObject source = sharedRecord.getJsonObject("marcPayload");
    final JsonObject inventory = sharedRecord.getJsonObject("inventoryPayload");
    return upsertBibRecord(conn, localIdentifier, sourceId, source, inventory);
  }

  /**
   * Upsert set of records.
   * @param request ingest record request
   * @return async result
   */
  public Future<Void> upsertSharedRecords(JsonObject request) {
    UUID sourceId = UUID.fromString(request.getString("sourceId"));
    JsonArray records = request.getJsonArray("records");
    return pool.getConnection().compose(conn -> {
      Future<Void> future = Future.succeededFuture();
      for (int i = 0; i < records.size(); i++) {
        JsonObject sharedRecord = records.getJsonObject(i);
        future = future.compose(x -> upsertSharedRecord(conn, sourceId, sharedRecord));
      }
      return future.eventually(y -> conn.close());
    });
  }

  /**
   * Get shared records.
   * @param ctx routing context
   * @param sqlWhere SQL where caluse
   * @param sqlOrderBy SQL order by clause
   * @return async result
   */
  public Future<Void> getSharedRecords(RoutingContext ctx, String sqlWhere, String sqlOrderBy) {
    String from = bibRecordTable;
    if (sqlWhere != null) {
      from = from + " WHERE " + sqlWhere;
    }
    return streamResult(ctx, null, from, sqlOrderBy, "items",
        row -> new JsonObject()
            .put("globalId", row.getUUID("id"))
            .put("localId", row.getString("local_identifier"))
            .put("sourceId", row.getUUID("library_id"))
            .put("payload", row.getJsonObject("source")));
  }

  /**
   * Insert match key into storage.
   * @param id match key id (user specified)
   * @param method match key method
   * @param params configuration
   * @return async result
   */
  public Future<Void> insertMatchKey(String id, String method, JsonObject params) {
    return pool.preparedQuery(
        "INSERT INTO " + matchKeyConfigTable + " (id, method, params) VALUES ($1, $2, $3)")
        .execute(Tuple.of(id, method, params))
        .mapEmpty();
  }

  /**
   * Select match key from storage.
   * @param id match key id (user specified)
   * @return JSON object if found; null if not found
   */
  public Future<JsonObject> selectMatchKey(String id) {
    return pool.preparedQuery(
            "SELECT * FROM " + matchKeyConfigTable + " WHERE id = $1")
        .execute(Tuple.of(id))
        .map(res -> {
          RowIterator<Row> iterator = res.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          Row row = iterator.next();
          return new JsonObject()
              .put("id", row.getString("id"))
              .put("method", row.getString("method"))
              .put("params", row.getJsonObject("params"));
        });
  }

  /**
   * Delete match key.
   * @param id match key identifier
   * @return TRUE if deleted; FALSE if not found
   */
  public Future<Boolean> deleteMatchKey(String id) {
    return pool.preparedQuery(
            "DELETE FROM " + matchKeyConfigTable + " WHERE id = $1")
        .execute(Tuple.of(id))
        .map(res -> res.rowCount() > 0);
  }

  /**
   * Get match keys.
   * @param ctx routing context
   * @param sqlWhere SQL where caluse
   * @param sqlOrderBy SQL order by clause
   * @return async result
   */
  public Future<Void> getMatchKeys(RoutingContext ctx, String sqlWhere, String sqlOrderBy) {
    String from = matchKeyConfigTable;
    if (sqlWhere != null) {
      from = from + " WHERE " + sqlWhere;
    }
    return streamResult(ctx, null, from, sqlOrderBy, "matchKeys",
        row -> new JsonObject()
            .put("id", row.getString("id"))
            .put("method", row.getString("method"))
            .put("params", row.getJsonObject("params")));
  }


  private static JsonObject copyWithoutNulls(JsonObject obj) {
    JsonObject n = new JsonObject();
    obj.getMap().forEach((key, value) -> {
      if (value != null) {
        n.put(key, value);
      }
    });
    return n;
  }

  static void resultFooter(RoutingContext ctx, RowSet<Row> rowSet, List<String[]> facets,
      String diagnostic) {

    JsonObject resultInfo = new JsonObject();
    int count = 0;
    JsonArray facetArray = new JsonArray();
    if (rowSet != null) {
      int pos = 0;
      Row row = rowSet.iterator().next();
      count = row.getInteger(pos);
      for (String [] facetEntry : facets) {
        pos++;
        JsonObject facetObj = null;
        final String facetType = facetEntry[0];
        final String facetValue = facetEntry[1];
        for (int i = 0; i < facetArray.size(); i++) {
          facetObj = facetArray.getJsonObject(i);
          if (facetType.equals(facetObj.getString("type"))) {
            break;
          }
          facetObj = null;
        }
        if (facetObj == null) {
          facetObj = new JsonObject();
          facetObj.put("type", facetType);
          facetObj.put("facetValues", new JsonArray());
          facetArray.add(facetObj);
        }
        JsonArray facetValues = facetObj.getJsonArray("facetValues");
        facetValues.add(new JsonObject()
            .put("value", facetValue)
            .put("count", row.getInteger(pos)));
      }
    }
    resultInfo.put("totalRecords", count);
    JsonArray diagnostics = new JsonArray();
    if (diagnostic != null) {
      diagnostics.add(new JsonObject().put("message", diagnostic));
    }
    resultInfo.put("diagnostics", diagnostics);
    resultInfo.put("facets", facetArray);
    ctx.response().write("], \"resultInfo\": " + resultInfo.encode() + "}");
    ctx.response().end();
  }

  Future<Void> streamResult(RoutingContext ctx, SqlConnection sqlConnection,
      String query, String cnt, String property, List<String[]> facets,
      Function<Row, JsonObject> handler) {

    return sqlConnection.prepare(query)
        .compose(pq ->
            sqlConnection.begin().compose(tx -> {
              ctx.response().setChunked(true);
              ctx.response().putHeader("Content-Type", "application/json");
              ctx.response().write("{ \"" + property + "\" : [");
              AtomicBoolean first = new AtomicBoolean(true);
              RowStream<Row> stream = pq.createStream(50);
              stream.handler(row -> {
                if (!first.getAndSet(false)) {
                  ctx.response().write(",");
                }
                JsonObject response = handler.apply(row);
                ctx.response().write(copyWithoutNulls(response).encode());
              });
              stream.endHandler(end -> sqlConnection.query(cnt).execute()
                  .onSuccess(cntRes -> resultFooter(ctx, cntRes, facets, null))
                  .onFailure(f -> {
                    log.error(f.getMessage(), f);
                    resultFooter(ctx, null, facets, f.getMessage());
                  })
                  .eventually(x -> tx.commit().compose(y -> sqlConnection.close())));
              stream.exceptionHandler(e -> {
                log.error("stream error {}", e.getMessage(), e);
                resultFooter(ctx, null, facets, e.getMessage());
                tx.commit().compose(y -> sqlConnection.close());
              });
              return Future.succeededFuture();
            })
        );
  }

  Future<Void> streamResult(
      RoutingContext ctx, String distinct,
      String from, String orderByClause, String property, Function<Row, JsonObject> handler) {

    return streamResult(ctx, distinct, distinct, List.of(from), Collections.emptyList(),
        orderByClause, property, handler);
  }

  @java.lang.SuppressWarnings({"squid:S107"})  // too many arguments
  Future<Void> streamResult(RoutingContext ctx, String distinctMain, String distinctCount,
      List<String> fromList, List<String[]> facets, String orderByClause,
      String property, Function<Row, JsonObject> handler) {

    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    Integer offset = params.queryParameter("offset").getInteger();
    Integer limit = params.queryParameter("limit").getInteger();
    String query = "SELECT " + (distinctMain != null ? "DISTINCT ON (" + distinctMain + ")" : "")
        + " * FROM " + fromList.get(0)
        + (orderByClause == null ?  "" : " ORDER BY " + orderByClause)
        + " LIMIT " + limit + " OFFSET " + offset;
    log.info("query={}", query);
    StringBuilder countQuery = new StringBuilder("SELECT");
    int pos = 0;
    for (String from : fromList) {
      if (pos > 0) {
        countQuery.append(",\n");
      }
      countQuery.append("(SELECT COUNT("
          + (distinctCount != null ? "DISTINCT " + distinctCount : "*")
          + ") FROM " + from + ") AS cnt" + pos);
      pos++;
    }
    log.info("cnt={}", countQuery);
    return pool.getConnection()
        .compose(sqlConnection -> streamResult(ctx, sqlConnection, query, countQuery.toString(),
            property, facets, handler)
            .onFailure(x -> sqlConnection.close()));
  }

}