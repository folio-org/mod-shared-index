package org.folio.shared.index.api;

import org.junit.Assert;
import org.junit.Test;

public class UtilTest {

  @Test
  public void dateParseFrom() {
    Assert.assertEquals("2022-04-12T00:00", Util.parseFrom("2022-04-12").toString());
    Assert.assertEquals("2022-04-12T10:20:30", Util.parseFrom("2022-04-12T10:20:30Z").toString());
    Assert.assertEquals("badArgument", Assert.assertThrows(OaiException.class,
        () -> Util.parseFrom("2022-04x")).getErrorCode());
  }

  @Test
  public void dateParseUntil() {
    Assert.assertEquals("2022-04-13T00:00", Util.parseUntil("2022-04-12").toString());
    Assert.assertEquals("2022-04-12T10:20:31", Util.parseUntil("2022-04-12T10:20:30Z").toString());
    Assert.assertEquals("badArgument", Assert.assertThrows(OaiException.class,
        () -> Util.parseUntil("2022-04x")).getErrorCode());
  }
}
