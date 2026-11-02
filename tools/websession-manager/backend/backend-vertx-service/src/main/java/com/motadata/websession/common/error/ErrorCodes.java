package com.motadata.websession.common.error;

public enum ErrorCodes {

  UNAUTHORIZED("UNAUTHORIZED"),
  FORBIDDEN("FORBIDDEN"),
  SESSION_EXPIRED("SESSION_EXPIRED"),
  INVALID_REQUEST("INVALID_REQUEST"),
  INVALID_CREDENTIALS("INVALID_CREDENTIALS"),;

  private final String code;

  ErrorCodes(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
