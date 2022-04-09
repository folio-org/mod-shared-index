package org.folio.shared.index.api;

import io.vertx.ext.web.validation.RequestParameter;
import io.vertx.ext.web.validation.RequestParameters;

public final class Util {

  static String getParameterString(RequestParameter parameter) {
    return parameter == null ? null : parameter.getString();
  }

  static String getQueryParameter(RequestParameters params) {
    return Util.getParameterString(params.queryParameter("query"));
  }

}
