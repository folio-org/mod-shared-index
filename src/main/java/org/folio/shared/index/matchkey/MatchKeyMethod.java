package org.folio.shared.index.matchkey;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import java.util.List;

public interface MatchKeyMethod {
  String getName();

  void configure(JsonObject configuration);

  List<String> getKeys(Buffer marcPayload, Buffer inventoryPayload);
}
