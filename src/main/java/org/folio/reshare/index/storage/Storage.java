package org.folio.reshare.index.storage;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import io.vertx.sqlclient.Row;
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

public class Storage {
  private static final Logger log = LogManager.getLogger(Storage.class);

  private static final String IDENTIFIER_TYPE_ID_ISBN = "8261054f-be78-422d-bd51-4ed9f33c3422";
  private static final String MATCH_TYPE_ID_ISBN = "034e3f52-0092-4b4e-8704-d5f56f5b28a1";

  TenantPgPool pool;
  String bibRecordTable;
  String matchTypeTable;
  String matchPointTable;
  String matchPointFunction;
  String matchPointTrigger;
  String itemView;

  /**
   * Create storage service for tenant.
   * @param vertx Vert.x hande
   * @param tenant tenant
   */
  public Storage(Vertx vertx, String tenant) {
    this.pool = TenantPgPool.pool(vertx,tenant);
    this.bibRecordTable = pool.getSchema() + ".bib_record";
    this.matchTypeTable = pool.getSchema() + ".match_type";
    this.matchPointTable = pool.getSchema() + ".match_point";
    this.matchPointFunction = pool.getSchema() + ".match_points()";
    this.matchPointTrigger = "match_points";
    this.itemView = pool.getSchema() + ".item_view";
  }

  /**
   * Prepares storage with tables, etc.
   * @return async result.
   */
  public Future<Void> init() {
    return pool.execute(List.of(
            "SET search_path TO " + pool.getSchema(),
            "CREATE TABLE IF NOT EXISTS " + bibRecordTable
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
            "CREATE TABLE IF NOT EXISTS " + matchTypeTable
                + "(id uuid NOT NULL PRIMARY KEY,"
                + " code VARCHAR, "
                + " name VARCHAR)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_match_type_code ON " + matchTypeTable
                + " (code)",
            "INSERT INTO " + matchTypeTable
                + " (id, code, name) "
                + " VALUES ('" + MATCH_TYPE_ID_ISBN + "', 'ISBN', 'ISBN') ON CONFLICT DO NOTHING",
            "CREATE TABLE IF NOT EXISTS " + matchPointTable
                + "(bib_record_id uuid NOT NULL,"
                + " match_type_id uuid NOT NULL,"
                + " match_value VARCHAR NOT NULL,"
                + " CONSTRAINT match_point_fk_bib_record FOREIGN KEY "
                + "                (bib_record_id) REFERENCES " + bibRecordTable
                + " ON DELETE CASCADE,"
                + " CONSTRAINT match_point_fk_type FOREIGN KEY "
                + "                (match_type_id) REFERENCES " + matchTypeTable + ")",
            "CREATE UNIQUE INDEX IF NOT EXISTS match_point_idx ON " + matchPointTable
                + " (match_type_id, match_value, bib_record_id)",
            "CREATE INDEX IF NOT EXISTS match_point_bib_id_idX ON " + matchPointTable
                + " (bib_record_id)",
            "CREATE OR REPLACE FUNCTION " + matchPointFunction
                + " RETURNS TRIGGER AS $match_points$"
                + " BEGIN"
                + "   IF (TG_OP = 'UPDATE') THEN"
                + "     DELETE FROM " + matchPointTable
                + "     WHERE bib_record_id = NEW.id;"
                + "   END IF;"
                + "   IF (TG_OP = 'UPDATE' OR TG_OP = 'INSERT') THEN"
                + "     INSERT INTO " + matchPointTable
                + "       (bib_record_id, match_type_id, match_value)"
                + "     SELECT NEW.id, '" + MATCH_TYPE_ID_ISBN + "', identifier->>'value'"
                + "     FROM jsonb_array_elements("
                + "           ((NEW.inventory->>'instance')::JSONB->>'identifiers')::JSONB) "
                + "            AS identifier"
                + "            WHERE identifier->>'identifierTypeId'='"
                +                    IDENTIFIER_TYPE_ID_ISBN + "';"
                + "   END IF;"
                + "   RETURN NULL;"
                + " END;"
                + " $match_points$ LANGUAGE plpgsql;",
            "DROP TRIGGER IF EXISTS " + matchPointTrigger + " ON " + bibRecordTable,
            "CREATE TRIGGER " + matchPointTrigger
                + " AFTER INSERT OR UPDATE ON " + bibRecordTable
                + " FOR EACH ROW EXECUTE PROCEDURE " + matchPointFunction
        )
    ).mapEmpty();
  }

  /**
   * Inserts or updates an entry in the shared index.
   */
  public Future<Void> upsertBibRecord(
      String localIdentifier,
      String libraryId,
      JsonObject source,
      JsonObject inventory) {

    return pool.preparedQuery(
        "INSERT INTO " + bibRecordTable
            + " (id, local_identifier, library_id, source, inventory)"
            + " VALUES ($1, $2, $3, $4, $5)"
            + " ON CONFLICT (local_identifier, library_id) DO UPDATE "
            + " SET source = $4, "
            + "     inventory = $5").execute(
        Tuple.of(UUID.randomUUID(), localIdentifier, libraryId, source, inventory)
    ).mapEmpty();
  }

  /**
   * Get shared titles with streaming result.
   * @param ctx routing context
   * @param cqlWhere WHERE clause for SQL (null for no clause)
   * @param orderBy  ORDER BY for SQL (null for no order)
   * @return async result and result written to routing context.
   */
  public Future<Void> getTitles(RoutingContext ctx, String cqlWhere, String orderBy) {
    String from = bibRecordTable;
    if (cqlWhere != null) {
      from = from + " WHERE " + cqlWhere;
    }
    return streamResult(ctx, null, from, orderBy, "titles",
        row -> new JsonObject()
            .put("id", row.getUUID("id"))
            .put("localIdentifier", row.getString("local_identifier"))
            .put("libraryId", row.getUUID("library_id")));
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

    return streamResult(ctx, distinct, distinct, List.of(from), Collections.EMPTY_LIST,
        orderByClause, property, handler);
  }

  Future<Void> streamResult(RoutingContext ctx, String distinctMain, String distinctCount,
      List<String> fromList, List<String[]> facets, String orderByClause,
      String property,
      Function<Row, JsonObject> handler) {

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
    log.info("cnt={}", countQuery.toString());
    return pool.getConnection()
        .compose(sqlConnection -> streamResult(ctx, sqlConnection, query, countQuery.toString(),
            property, facets, handler)
            .onFailure(x -> sqlConnection.close()));
  }


}
