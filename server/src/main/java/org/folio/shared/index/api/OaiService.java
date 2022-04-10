package org.folio.shared.index.api;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.shared.index.storage.Storage;
import org.folio.shared.index.util.XmlJsonUtil;

public final class OaiService {
  private static final Logger log = LogManager.getLogger(OaiService.class);

  private OaiService() { }

  static final String OAI_HEADER =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\"\n"
          + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
          + "         xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/\n"
          + "         http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd\">\n";

  static void oaiHeader(RoutingContext ctx, int httpStatus) {
    HttpServerResponse response = ctx.response();
    response.setChunked(true);
    response.setStatusCode(httpStatus);
    response.putHeader("Content-Type", "text/xml");
    response.write(OAI_HEADER);
    response.write("  <responseDate>" + Instant.now() + "</responseDate>\n");
    response.write("  <request>"
        + XmlJsonUtil.encodeXmlText(ctx.request().absoluteURI())
        + "</request>\n");
  }

  static void oaiFooter(RoutingContext ctx) {
    HttpServerResponse response = ctx.response();
    response.write("</OAI-PMH>");
    response.end();
  }

  static Future<Void> get(RoutingContext ctx) {
    return getCheck(ctx).recover(e -> {
      if (ctx.response().headWritten()) {
        return Future.succeededFuture();
      }
      String errorCode;
      if (e instanceof OaiException) {
        oaiHeader(ctx, 400);
        errorCode = ((OaiException) e).getErrorCode();
      } else {
        oaiHeader(ctx, 500);
        errorCode = "internal";
      }
      ctx.response().write("  <error code=\"" + errorCode + "\">"
          + XmlJsonUtil.encodeXmlText(e.getMessage()) + "</error>\n");
      oaiFooter(ctx);
      return Future.succeededFuture();
    });
  }

  static Future<Void> getCheck(RoutingContext ctx) {
    try {
      RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
      String verb = Util.getParameterString(params.queryParameter("verb"));
      if (verb == null) {
        throw OaiException.badVerb("missing verb");
      }
      switch (verb) {
        case "ListRecords":
          return listRecords(ctx);
        case "GetRecord":
          return getRecord(ctx);
        default:
          throw OaiException.badVerb(verb);
      }
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  static Future<Void> listRecords(RoutingContext ctx) {
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    String set = Util.getParameterString(params.queryParameter("set"));
    if (set == null) {
      throw OaiException.badArgument("set or resumptionToken missing");
    }
    String metadataPrefix = Util.getParameterString(params.queryParameter("metadataPrefix"));
    if (!"marcxml".equals(metadataPrefix)) {
      throw OaiException.cannotDisseminateFormat("only metadataPrefix \"marcxml\" supported");
    }
    Storage storage = new Storage(ctx);
    return storage.selectMatchKeyConfig(set).compose(conf -> {
      if (conf == null) {
        throw OaiException.badArgument("set \"" + set + "\" not found");
      }
      List<Object> tupleList = new ArrayList<>();
      tupleList.add(set);
      StringBuilder sqlQuery = new StringBuilder("SELECT * FROM " + storage.getClusterMetaTable()
          + " WHERE match_key_config_id = $1");
      String from = Util.getParameterString(params.queryParameter("from"));
      int no = 2;
      if (from != null) {
        tupleList.add(LocalDateTime.parse(from));
        sqlQuery.append(" AND modified >= $" + no);
        no++;
      }
      String until = Util.getParameterString(params.queryParameter("until"));
      if (until != null) {
        tupleList.add(LocalDateTime.parse(until));
        sqlQuery.append(" AND modified < $" + no);
      }
      return storage.getPool().getConnection().compose(conn ->
          listRecordsResponse(ctx, storage, conn, sqlQuery.toString(), Tuple.from(tupleList))
      );
    });
  }

  private static void endListResponse(RoutingContext ctx, SqlConnection conn, Transaction tx) {
    ctx.response().write("  </ListRecords>\n");
    oaiFooter(ctx);
    tx.commit().compose(y -> conn.close());
  }

  static Future<String> getXmlRecord(Storage storage, SqlConnection conn, UUID clusterId,
      LocalDateTime datestamp) {
    String xml = "    <record>\n"
        + "      <header>\n"
        + "        <identifier>"
        // clusterId in itself is not a valid identifier
        + XmlJsonUtil.encodeXmlText(clusterId.toString())
        + "</identifier>\n"
        + "        <datestamp>"
        + XmlJsonUtil.encodeXmlText(datestamp.atZone(ZoneOffset.UTC).toString())
        + "</datestamp>\n"
        + "      </header>\n"
        + "    </record>\n";
    return Future.succeededFuture(xml);
  }

  static Future<Void> listRecordsResponse(RoutingContext ctx, Storage storage, SqlConnection conn,
      String sqlQuery, Tuple tuple) {
    return conn.prepare(sqlQuery).compose(pq ->
        conn.begin().compose(tx -> {
          HttpServerResponse response = ctx.response();
          oaiHeader(ctx, 200);
          response.write("  <request verb=\"ListRecords\">");
          response.write(XmlJsonUtil.encodeXmlText(ctx.request().absoluteURI()));
          response.write("</request>\n");
          response.write("  <ListRecords>\n");
          RowStream<Row> stream = pq.createStream(100, tuple);
          stream.handler(row -> {
            stream.pause();
            getXmlRecord(storage, conn,
                row.getUUID("cluster_id"), row.getLocalDateTime("datestamp"))
                .onSuccess(xmlRecord ->
                  response.write(xmlRecord).onComplete(x -> stream.resume())
                )
                .onFailure(e -> {
                  log.info("failure {}", e.getMessage(), e);
                  stream.close();
                  conn.close();
                });
          });
          stream.endHandler(end -> endListResponse(ctx, conn, tx));
          stream.exceptionHandler(e -> {
            log.error("stream error {}", e.getMessage(), e);
            endListResponse(ctx, conn, tx);
          });
          return Future.succeededFuture();
        })
    );
  }

  static Future<Void> getRecord(RoutingContext ctx) {
    return Future.failedFuture("Not implemented");
  }

}
