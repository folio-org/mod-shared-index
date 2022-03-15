package org.folio.shared.index.client;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
  static final Logger log = LogManager.getLogger(Main.class);

  private static String getArgument(String [] args, int i) {
    if (i >= args.length) {
      throw new RuntimeException("Missing argument for option '" + args[i - 1] + "'");
    }
    return args[i];
  }

  /** Execute command line shared-index client.
   *
   * @param vertx Vertx. handle
   * @param webClient web client
   * @param args command line args
   * @return async result
   */
  public static Future<Void> mainClient(Vertx vertx, WebClient webClient, String[] args) {
    try {
      Client client = new Client(webClient, vertx);
      Future<Void> future = Future.succeededFuture();
      for (int i = 0; i < args.length; i++) {
        if (args[i].startsWith("--")) {
          switch (args[i].substring(2)) {
            case "help":
              System.out.println("[options] [file..]");
              System.out.println(" --source sourceId   (defaults to random UUID)");
              System.out.println(" --okapiurl url      (defaults to http://localhost:9130)");
              System.out.println(" --tenant tenant     (defaults to \"testlib\")");
              System.out.println(" --chunk sz          (defaults to 1)");
              break;
            case "source":
              client.setSourceId(UUID.fromString(getArgument(args, ++i)));
              break;
            case "okapiurl":
              client.setOkapiUrl(getArgument(args, ++i));
              break;
            case "tenant":
              client.setTenant(getArgument(args, ++i));
              break;
            case "chunk":
              client.setChunkSize(Integer.parseInt(getArgument(args, ++i)));
              break;
            default:
              throw new RuntimeException("Unsupported option: '" + args[i] + "'");
          }
        } else {
          String fname = args[i];
          future = future.compose(x -> client.sendFile(fname));
        }
      }
      return future;
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  /**
   * Main program for client.
   * @param args command-line args
   */
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    WebClient webClient = WebClient.create(vertx);
    mainClient(vertx, webClient, args)
        .eventually(x -> {
          webClient.close();
          vertx.close();
          return Future.succeededFuture();
        })
        .onFailure(e -> {
          log.error(e.getMessage(), e);
          System.exit(1);
        });
  }
}
