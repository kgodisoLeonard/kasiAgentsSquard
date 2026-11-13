package com.spaza.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/** Returns every API failure as {"error": "..."} so the frontend can show a readable message. */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({IllegalArgumentException.class, ClassCastException.class})
  ResponseEntity<Map<String,Object>> badRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e instanceof ClassCastException ? "Invalid request values" : e.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String,Object>> unreadable(HttpMessageNotReadableException e) {
    return body(HttpStatus.BAD_REQUEST, "Invalid request body");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String,Object>> mediaType(HttpMediaTypeNotSupportedException e) {
    return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Requests must be sent as JSON");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String,Object>> method(HttpRequestMethodNotSupportedException e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String,Object>> notFound(NoSuchElementException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(com.spaza.security.AuthService.AuthException.class)
  ResponseEntity<Map<String,Object>> auth(com.spaza.security.AuthService.AuthException e) {
    return body(HttpStatus.valueOf(e.status()), e.getMessage());
  }

  @ExceptionHandler(com.spaza.security.AuthService.StepUpRequired.class)
  ResponseEntity<Map<String,Object>> stepUp(com.spaza.security.AuthService.StepUpRequired e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage(), "code", "STEP_UP_REQUIRED", "action", e.action()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String,Object>> forbidden(SecurityException e) {
    return body(HttpStatus.FORBIDDEN, e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String,Object>> conflict(IllegalStateException e) {
    return body(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String,Object>> unexpected(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse framework) {
      // Spring's own errors (404 no resource, 400 missing params, ...) keep their real status.
      HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
      return body(status, status.getReasonPhrase());
    }
    log.error("Unhandled API error", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server. Please try again.");
  }

  private static ResponseEntity<Map<String,Object>> body(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message == null || message.isBlank() ? status.getReasonPhrase() : message));
  }
}
