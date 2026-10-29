package de.sdmxdw.web.rest;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Plain-text errors with the status codes the SDMX REST specification prescribes. */
@RestControllerAdvice(basePackageClasses = SdmxRestExceptionHandler.class)
public class SdmxRestExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> badRequest(IllegalArgumentException e) {
    return text(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<String> notFound(NoSuchElementException e) {
    return text(HttpStatus.NOT_FOUND, e.getMessage() == null ? "No results found" : e.getMessage());
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ResponseEntity<String> notImplemented(UnsupportedOperationException e) {
    return text(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
  }

  private static ResponseEntity<String> text(HttpStatus status, String message) {
    return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message + "\n");
  }
}
