package org.folio.shared.index;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.tlib.postgres.testing.TenantPgPoolContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class MainVerticleTest {
  private final static Logger log = LogManager.getLogger("MainVerticleTest");

  static Vertx vertx;
  static final int MODULE_PORT = 9230;

  @ClassRule
  public static PostgreSQLContainer<?> postgresSQLContainer = TenantPgPoolContainer.create();

  @BeforeClass
  public static void beforeClass(TestContext context) {
    vertx = Vertx.vertx();
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    RestAssured.baseURI = "http://localhost:" + MODULE_PORT;
    RestAssured.requestSpecification = new RequestSpecBuilder().build();

    DeploymentOptions deploymentOptions = new DeploymentOptions();
    deploymentOptions.setConfig(new JsonObject().put("port", Integer.toString(MODULE_PORT)));
    vertx.deployVerticle(new MainVerticle(), deploymentOptions).onComplete(context.asyncAssertSuccess());
  }

  /**
   * Test utility for calling tenant init
   * @param context test context
   * @param tenant tenant that we're dealing with.
   * @param tenantAttributes tenant attributes as it would come from Okapi install.
   * @param expectedError error to expect (null for expecting no error)
   */
  void tenantOp(TestContext context, String tenant, JsonObject tenantAttributes, String expectedError) {
    ExtractableResponse<Response> response = RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(tenantAttributes.encode())
        .post("/_/tenant")
        .then().statusCode(201)
        .header("Content-Type", is("application/json"))
        .body("tenant", is(tenant))
        .extract();

    String location = response.header("Location");
    JsonObject tenantJob = new JsonObject(response.asString());
    context.assertEquals("/_/tenant/" + tenantJob.getString("id"), location);

    response = RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .get(location + "?wait=10000")
        .then().statusCode(200)
        .extract();

    context.assertTrue(response.path("complete"));
    context.assertEquals(expectedError, response.path("error"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .delete(location)
        .then().statusCode(204);
  }


  @AfterClass
  public static void afterClass(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void testAdminHealth() {
    RestAssured.given()
        .get("/admin/health")
        .then().statusCode(200)
        .header("Content-Type", is("text/plain"));
  }

  @Test
  public void testGetSharedRecordsUnknownTenant() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, "unknowntenant")
        .get("/shared-index/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(is("ERROR: relation \"unknowntenant_mod_shared_index.bib_record\" does not exist (42P01)"));
  }

  @Test
  public void testGetSharedRecordsBadCqlField() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, "unknowntenant")
        .get("/shared-index/records?query=foo=bar")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(is("Unsupported CQL index: foo"));
  }

  @Test
  public void testBadTenantName() {
    String tenant = "1234"; // bad tenant name!

    String sourceId = UUID.randomUUID().toString();
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "1"))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/shared-index/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(containsString("X-Okapi-Tenant header must match"));
  }

  @Test
  public void putSharedTitleUnknownTenant() {
    String sourceId = UUID.randomUUID().toString();
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "1"))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, "unknowntenant")
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/shared-index/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(is("ERROR: relation \"unknowntenant_mod_shared_index.bib_record\" does not exist (42P01)"));
  }
  @Test
  public void putSharedRecords(TestContext context) {
    String tenant = "tenant3";
    tenantOp(context, tenant, new JsonObject().put("module_to", "mod-shared-index-1.0.0"), null);

    String sourceId = UUID.randomUUID().toString();
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "1"))
        )
        .add(new JsonObject()
            .put("localId", "HRID02")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "2"))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/shared-index/records")
        .then().statusCode(200);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .get("/shared-index/records")
        .then().statusCode(200)
        .body("items", hasSize(2))
        .body("items[0].sourceId", is(sourceId))
        .body("items[1].sourceId", is(sourceId))
        .body("resultInfo.totalRecords", is(2));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .get("/shared-index/records?query=sourceId==" + sourceId)
        .then().statusCode(200)
        .body("items", hasSize(2))
        .body("items[0].sourceId", is(sourceId))
        .body("items[1].sourceId", is(sourceId))
        .body("resultInfo.totalRecords", is(2));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .get("/shared-index/records?query=sourceId==" + UUID.randomUUID())
        .then().statusCode(200)
        .body("items", hasSize(0))
        .body("resultInfo.totalRecords", is(0));

    for (int idx = 0; idx < records.size(); idx++) {
      JsonObject sharedRecord = records.getJsonObject(idx);
      RestAssured.given()
          .header(XOkapiHeaders.TENANT, tenant)
          .header("Content-Type", "application/json")
          .body(request.encode())
          .get("/shared-index/records?query=localId==" + sharedRecord.getString("localId"))
          .then().statusCode(200)
          .body("items", hasSize(1))
          .body("items[0].localId", is(sharedRecord.getString("localId")))
          .body("items[0].payload.leader", is(sharedRecord.getJsonObject("marcPayload").getString("leader")))
          .body("items[0].sourceId", is(sourceId))
          .body("resultInfo.totalRecords", is(1));
    }
    tenantOp(context, tenant, new JsonObject().put("module_from", "mod-shared-index-1.0.0"), null);
  }

  @Test
  public void upgradeDb(TestContext context) {
    String tenant = "tenant3";
    tenantOp(context, tenant, new JsonObject()
        .put("module_to", "mod-shared-index-1.0.0"), null);
    tenantOp(context, tenant, new JsonObject()
        .put("module_from", "mod-shared-index-1.0.0")
        .put("module_to", "mod-shared-index-1.0.1"), null);
  }


}
