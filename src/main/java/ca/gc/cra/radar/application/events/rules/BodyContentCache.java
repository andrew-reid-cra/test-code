package ca.gc.cra.radar.application.events.rules;

import ca.gc.cra.radar.application.events.http.HttpBodyView;
import ca.gc.cra.radar.application.events.http.HttpExchangeContext;
import ca.gc.cra.radar.application.events.http.HttpResponseContext;
import ca.gc.cra.radar.application.events.json.JsonSupport;
import java.util.Optional;

/**
 * Lazily materializes request/response body representations for rule evaluation.
 *
 * @since RADAR 1.1.0
 */
final class BodyContentCache {
  private final HttpExchangeContext exchange;
  private final JsonSupport jsonSupport;

  private volatile String requestBody;
  private volatile boolean requestBodyLoaded;
  private volatile Object requestJson;
  private volatile boolean requestJsonLoaded;

  private volatile String responseBody;
  private volatile boolean responseBodyLoaded;
  private volatile Object responseJson;
  private volatile boolean responseJsonLoaded;

  BodyContentCache(HttpExchangeContext exchange, JsonSupport jsonSupport) {
    this.exchange = exchange;
    this.jsonSupport = jsonSupport;
  }

  Optional<String> requestBodyString() {
    if (requestBodyLoaded) {
      return requestBody == null ? Optional.empty() : Optional.of(requestBody);
    }
    synchronized (this) {
      if (!requestBodyLoaded) {
        HttpBodyView view = exchange.request().body();
        requestBody = view.isEmpty() ? null : view.asString();
        requestBodyLoaded = true;
      }
      return requestBody == null ? Optional.empty() : Optional.of(requestBody);
    }
  }

  Optional<Object> requestJson() {
    if (jsonSupport == null) {
      return Optional.empty();
    }
    if (requestJsonLoaded) {
      return requestJson == null ? Optional.empty() : Optional.of(requestJson);
    }
    synchronized (this) {
      if (!requestJsonLoaded) {
        HttpBodyView view = exchange.request().body();
        requestJson = view.asJson(jsonSupport).orElse(null);
        requestJsonLoaded = true;
      }
      return requestJson == null ? Optional.empty() : Optional.of(requestJson);
    }
  }

  Optional<String> responseBodyString() {
    HttpResponseContext response = exchange.response();
    if (response == null) {
      return Optional.empty();
    }
    if (responseBodyLoaded) {
      return responseBody == null ? Optional.empty() : Optional.of(responseBody);
    }
    synchronized (this) {
      if (!responseBodyLoaded) {
        HttpBodyView view = response.body();
        responseBody = view.isEmpty() ? null : view.asString();
        responseBodyLoaded = true;
      }
      return responseBody == null ? Optional.empty() : Optional.of(responseBody);
    }
  }

  Optional<Object> responseJson() {
    if (jsonSupport == null) {
      return Optional.empty();
    }
    HttpResponseContext response = exchange.response();
    if (response == null) {
      return Optional.empty();
    }
    if (responseJsonLoaded) {
      return responseJson == null ? Optional.empty() : Optional.of(responseJson);
    }
    synchronized (this) {
      if (!responseJsonLoaded) {
        HttpBodyView view = response.body();
        responseJson = view.asJson(jsonSupport).orElse(null);
        responseJsonLoaded = true;
      }
      return responseJson == null ? Optional.empty() : Optional.of(responseJson);
    }
  }
}
