package com.motadata.websession.common.validator;

import com.motadata.websession.common.error.ApiException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class PayloadValidator {

  private final JsonObject payload;
  private final List<Rule> rules = new ArrayList<>();

  private record Rule(Predicate<JsonObject> condition, String message) {}

  private PayloadValidator(JsonObject payload) {
    this.payload = payload;
  }

  public static PayloadValidator of(JsonObject payload) {
    return new PayloadValidator(payload);
  }

  public PayloadValidator requireNonEmpty(String fieldName, String errorMessage) {
    rules.add(new Rule(
      p -> {
        String val = p.getString(fieldName);
        return val == null || val.trim().isEmpty();
      },
      errorMessage
    ));
    return this;
  }

 public PayloadValidator requirePort(String fieldName, int defaultPort,  String errorMessage)
 {
   rules.add(new Rule(p->
   {
     int port = p.getInteger(fieldName,defaultPort);
     return port<1 || port > 65535;
   },
     errorMessage
   ));

   return this;
 }

  public PayloadValidator validateIfPresent(String fieldName, Predicate<Object> validator, String errorMessage) {
    rules.add(new Rule(
      p -> p.containsKey(fieldName) && p.getValue(fieldName) != null && !validator.test(p.getValue(fieldName)),
      errorMessage
    ));
    return this;
  }

  public Future<Void> validate() {
    if (payload == null) {
      return Future.failedFuture(new ApiException(400, "INVALID_REQUEST", "Payload cannot be null"));
    }

    for (Rule rule : rules) {
      if (rule.condition().test(payload)) {
        return Future.failedFuture(new ApiException(400, "INVALID_REQUEST", rule.message()));
      }
    }
    return Future.succeededFuture();
  }

  public PayloadValidator requireAnyNonEmpty(List<String> fieldNames, String errorMessage) {
    rules.add(new Rule(
      p -> {
        for (String field : fieldNames) {
          String val = p.getString(field);
          if (val != null && !val.trim().isEmpty()) {
            return false; // Found a valid non-empty field
          }
        }
        return true; // None of the fields were valid
      },
      errorMessage
    ));
    return this;
  }
}
