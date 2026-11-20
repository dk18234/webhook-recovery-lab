package dev.dineshrama.webhook;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemHandler {
    @ExceptionHandler(ApiProblem.class) public ResponseEntity<?> known(ApiProblem e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> badRequest(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","The request body or parameters are invalid.")); }
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class,HttpRequestMethodNotSupportedException.class,NoResourceFoundException.class})
    public ResponseEntity<?> protocol(Exception e) {
        var failure=(ErrorResponse)e;
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("code","HTTP_"+failure.getStatusCode().value(),"message","The requested route, method, or content type is unsupported."));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e) {
        LoggerFactory.getLogger(ProblemHandler.class).error("Request failed",e);
        return ResponseEntity.status(500).body(Map.of("code","INTERNAL_ERROR","message","Request could not be completed. Retry using the same event ID if acceptance is uncertain."));
    }
}
