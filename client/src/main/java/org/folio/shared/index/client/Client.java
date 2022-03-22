package org.folio.shared.index.client;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.shared.index.util.XmlJsonUtil;
import org.marc4j.MarcJsonWriter;
import org.marc4j.MarcStreamReader;
import org.marc4j.converter.impl.AnselToUnicode;
import org.xml.sax.SAXException;

public class Client {
  static final Logger log = LogManager.getLogger(Client.class);

  UUID sourceId = UUID.randomUUID();
  String tenant = "testlib";
  String okapiUrl = "http://localhost:9130";
  int chunkSize = 1;
  Integer localSequence = 0;
  WebClient webClient;
  Map<String,String> headers = new HashMap<>();
  TransformerFactory transformerFactory = TransformerFactory.newInstance();
  List<Transformer> transformers = new LinkedList<>();

  public Client(WebClient webClient) {
    this.webClient = webClient;
  }

  public void setSourceId(UUID sourceId) {
    this.sourceId = sourceId;
  }

  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize;
  }

  public void setTenant(String tenant) {
    headers.put(XOkapiHeaders.TENANT, tenant);
  }

  public void setOkapiUrl(String okapiUrl) {
    this.okapiUrl = okapiUrl;
  }

  private void sendIso2709Chunk(MarcStreamReader reader, Promise<Void> promise) {
    JsonArray records = new JsonArray();
    while (reader.hasNext() && records.size() < chunkSize) {
      org.marc4j.marc.Record marcRecord = reader.next();
      char charCodingScheme = marcRecord.getLeader().getCharCodingScheme();
      if (charCodingScheme == ' ') {
        marcRecord.getLeader().setCharCodingScheme('a');
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MarcJsonWriter writer = new MarcJsonWriter(out, MarcJsonWriter.MARC_IN_JSON);
      if (charCodingScheme == ' ') {
        writer.setConverter(new AnselToUnicode());
      }
      writer.write(marcRecord);
      JsonObject marcPayload = new JsonObject(out.toString());
      JsonObject inventoryPayload = new JsonObject();
      records.add(new JsonObject()
          .put("localId", Integer.toString(localSequence++))
          .put("marcPayload", marcPayload)
          .put("inventoryPayload", inventoryPayload));
    }
    if (records.isEmpty()) {
      promise.complete();
      return;
    }
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    if ((localSequence % 1000) == 0) {
      log.info("{}", localSequence);
    }
    webClient.putAbs(okapiUrl + "/shared-index/records")
        .putHeader(XOkapiHeaders.TENANT, tenant)
        .putHeader(XOkapiHeaders.URL, okapiUrl)
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON)
        .sendJsonObject(request)
        .onFailure(promise::fail)
        .onSuccess(x -> sendIso2709Chunk(reader, promise));
  }

  private static JsonObject createIngestRecord(String marcXml, List<Transformer> transformers)
      throws TransformerException, ParserConfigurationException,
      IOException, SAXException, XMLStreamException {

    String inventory = marcXml;
    for (Transformer transformer : transformers) {
      Source source = new StreamSource(new StringReader(inventory));
      StreamResult result = new StreamResult(new StringWriter());
      transformer.transform(source, result);
      inventory = result.getWriter().toString();
    }
    JsonObject marcPayload = XmlJsonUtil.convertMarcXmlToJson(marcXml);
    JsonObject inventoryPayload;
    if (transformers.isEmpty()) {
      inventoryPayload = new JsonObject();
    } else {
      inventoryPayload = XmlJsonUtil.inventoryXmlToJson(inventory);
      log.info("inventoryPayload {}", inventoryPayload.encodePrettily());
    }
    return new JsonObject()
        .put("localId", "foo")
        .put("marcPayload", marcPayload)
        .put("inventoryPayload", inventoryPayload);
  }

  private void sendMarcXmlChunk(XMLStreamReader stream, Promise<Void> promise)  {
    JsonArray records = new JsonArray();
    try {
      while (stream.hasNext() && records.size() < chunkSize) {
        int event = stream.next();
        if (event == XMLStreamConstants.START_ELEMENT && "record".equals(stream.getLocalName())) {
          String marcXml = XmlJsonUtil.getSubDocument(event, stream);
          records.add(createIngestRecord(marcXml, transformers));
        }
      }
    } catch (Exception e) {
      promise.fail(e);
      return;
    }
    if (records.isEmpty()) {
      log.info("{}", localSequence);
      promise.complete();
      return;
    }
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    if ((localSequence % 1000) == 0) {
      log.info("{}", localSequence);
    }
    webClient.putAbs(okapiUrl + "/shared-index/records")
        .putHeader(XOkapiHeaders.TENANT, tenant)
        .putHeader(XOkapiHeaders.URL, okapiUrl)
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON)
        .sendJsonObject(request)
        .onFailure(promise::fail)
        .onSuccess(x -> sendMarcXmlChunk(stream, promise));
  }

  /**
   * Initialize data for the tenant.
   * @return async result
   */
  public Future<Void> init() {
    JsonObject request = new JsonObject()
        .put("module_to", "mod-shared-index-1.0.0");
    return tenantOp(request);
  }

  /**
   * Initialize data for the tenant.
   * @return async result
   */
  public Future<Void> purge() {
    JsonObject request = new JsonObject()
        .put("purge", Boolean.TRUE)
        .put("module_to", "mod-shared-index-1.0.0");
    return tenantOp(request);
  }

  private Future<Void> tenantOp(JsonObject request) {
    return webClient.postAbs(okapiUrl + "/_/tenant")
        .putHeader(XOkapiHeaders.TENANT, tenant)
        .putHeader(XOkapiHeaders.URL, okapiUrl)
        .sendJsonObject(request).compose(res -> {
          if (res.statusCode() == 204 || res.statusCode() == 200) {
            return Future.succeededFuture();
          } else if (res.statusCode() != 201) {
            throw new ClientException("For /_/tenant got status code " + res.statusCode());
          }
          String id = res.bodyAsJsonObject().getString("id");
          return webClient.getAbs(okapiUrl + "/_/tenant/" + id + "?wait=10000")
              .putHeader(XOkapiHeaders.TENANT, tenant)
              .putHeader(XOkapiHeaders.URL, okapiUrl)
              .expect(ResponsePredicate.SC_OK)
              .expect(ResponsePredicate.JSON).send()
              .compose(res2 -> {
                if (Boolean.FALSE.equals(res2.bodyAsJsonObject().getBoolean("complete"))) {
                  throw new ClientException("Incomplete job");
                }
                String error = res2.bodyAsJsonObject().getString("error");
                if (error != null) {
                  return Future.failedFuture(error);
                }
                return Future.succeededFuture();
              })
              .compose(x ->
                webClient.deleteAbs(okapiUrl + "/_/tenant/" + id)
                    .putHeader(XOkapiHeaders.TENANT, tenant)
                    .putHeader(XOkapiHeaders.URL, okapiUrl)
                    .expect(ResponsePredicate.SC_NO_CONTENT)
                    .send().mapEmpty()
              );
        });
  }

  Future<Void> sendIso2709(InputStream stream) {
    return Future.<Void>future(p -> sendIso2709Chunk(new MarcStreamReader(stream), p))
        .eventually(x -> {
          try {
            stream.close();
            return Future.succeededFuture();
          } catch (IOException e) {
            return Future.failedFuture(e);
          }
        });
  }

  Future<Void> sendMarcXml(InputStream stream) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    return Future.<Void>future(p -> sendMarcXmlChunk(xmlStreamReader, p))
        .eventually(x -> {
          try {
            stream.close();
            return Future.succeededFuture();
          } catch (IOException e) {
            return Future.failedFuture(e);
          }
        });
  }

  /**
   * Send file to shared-index server.
   * @param fname filename
   * @return async result
   */
  @java.lang.SuppressWarnings({"squid:S2095"}) // Resources should be closed
  // stream.close in eventually , *not* in finally as that would premature close the stream.
  public Future<Void> sendFile(String fname) {
    try {
      InputStream stream = new FileInputStream(fname);
      if (fname.endsWith(".rec") || fname.endsWith(".marc") || fname.endsWith(".mrc")) {
        return sendIso2709(stream);
      } else if (fname.endsWith(".xml")) {
        return sendMarcXml(stream);
      } else {
        stream.close();
        return Future.failedFuture("filename '" + fname + "' must be end with"
            + " .xml (marcxml) or .rec (iso2709)");
      }
    } catch (XMLStreamException | IOException e) {
      return Future.failedFuture(e);
    }
  }

  /**
   * Add XSLT to the be used for each record.
   * @param fname filename of XSL stylesheet
   * @return async result
   */
  public Future<Void> setXslt(String fname) {
    try {
      Source xslt = new StreamSource(fname);
      transformers.add(transformerFactory.newTransformer(xslt));
      return Future.succeededFuture();
    } catch (TransformerConfigurationException e) {
      return Future.failedFuture(e);
    }
  }

  private static String getArgument(String [] args, int i) {
    if (i >= args.length) {
      throw new ClientException("Missing argument for option '" + args[i - 1] + "'");
    }
    return args[i];
  }

  /** Execute command line shared-index client.
   *
   * @param webClient web client
   * @param args command line args
   * @return async result
   */
  public static Future<Void> exec(WebClient webClient, String[] args) {
    try {
      Client client = new Client(webClient);
      Future<Void> future = Future.succeededFuture();
      int i = 0;
      while (i < args.length) {
        String arg;
        if (args[i].startsWith("--")) {
          switch (args[i].substring(2)) {
            case "help":
              log.info("[options] [file..]");
              log.info(" --source sourceId   (defaults to random UUID)");
              log.info(" --okapiurl url      (defaults to http://localhost:9130)");
              log.info(" --tenant tenant     (defaults to \"testlib\")");
              log.info(" --chunk sz          (defaults to 1)");
              log.info("  -xsl file          (xslt transform for inventory payload)");
              log.info(" --init");
              log.info(" --purge");
              break;
            case "source":
              arg = getArgument(args, ++i);
              client.setSourceId(UUID.fromString(arg));
              break;
            case "okapiurl":
              arg = getArgument(args, ++i);
              client.setOkapiUrl(arg);
              break;
            case "tenant":
              arg = getArgument(args, ++i);
              client.setTenant(arg);
              break;
            case "chunk":
              arg = getArgument(args, ++i);
              client.setChunkSize(Integer.parseInt(arg));
              break;
            case "xsl":
              arg = getArgument(args, ++i);
              future = future.compose(x -> client.setXslt(arg));
              break;
            case "init":
              future = future.compose(x -> client.init());
              break;
            case "purge":
              future = future.compose(x -> client.purge());
              break;
            default:
              throw new ClientException("Unsupported option: '" + args[i] + "'");
          }
        } else {
          arg = args[i];
          future = future.compose(x -> client.sendFile(arg));
        }
        i++;
      }
      return future;
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }
}
