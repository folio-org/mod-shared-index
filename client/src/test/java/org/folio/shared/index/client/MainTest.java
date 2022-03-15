package org.folio.shared.index.client;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class MainTest {

  private static final int PORT = 9230;
  Vertx vertx;
  WebClient webClient;

  @Before
  public void before() {
    vertx = Vertx.vertx();
    webClient = WebClient.create(vertx);
  }

  @After
  public void before(TestContext context) {
    webClient.close();
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void noArgs() {
    String [] args = {};
    Main.main(args);
  }

  @Test
  public void fileNotFound(TestContext context) {
    String [] args = { "unknownfile" };
    Client.exec(vertx, webClient, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("java.io.FileNotFoundException: unknownfile (No such file or directory)", x.getMessage());
    }));
  }

  @Test
  public void badArgs(TestContext context) {
    String [] args = { "--bad", "value" };
    Client.exec(vertx, webClient, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("Unsupported option: '--bad'", x.getMessage());
    }));
  }

  @Test
  public void missingArgs(TestContext context) {
    String [] args = { "--chunk" };
    Client.exec(vertx, webClient, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("Missing argument for option '--chunk'", x.getMessage());
    }));
  }

  @Test
  public void help(TestContext context) {
    String [] args = { "--help" };
    Client.exec(vertx, webClient, args).onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void server(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/shared-index/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.getBodyAsJson());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    UUID sourceId = UUID.randomUUID();
    String [] args =
        { "--okapiurl", "http://localhost:" + PORT,
            "--chunk", "2",
            "--tenant", "testlib",
            "--source", sourceId.toString(),
            "src/test/resources/marc3.marc"};
    future = future.compose(x -> Client.exec(vertx, webClient, args));

    future.eventually(x -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(2, requests.size()); // two requests

          // first chunk with 2 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
          // second with 1 record
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(1, r.getJsonArray("records").size());
        }));
  }
}
