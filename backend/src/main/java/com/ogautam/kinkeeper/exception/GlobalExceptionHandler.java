package com.ogautam.kinkeeper.exception;

import com.google.firebase.auth.FirebaseAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FirebaseAuthException.class)
    public ResponseEntity<?> handleFirebaseAuth(FirebaseAuthException e) {
        log.warn("Firebase auth error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication failed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        log.info("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", safeMessage(e)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentNotValidException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> handleBadPayload(Exception e) {
        log.info("Malformed request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Request body was rejected: " + safeMessage(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception e) {
        // reason: homelab single-tenant app — the user gets more value from the
        // actual exception class + message than from a blanket "Internal server
        // error". Surface both, plus a short correlation id so the user can grep
        // Loki with one click if they report a problem.
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled {} [id={}]: {}",
                e.getClass().getSimpleName(), correlationId, e.getMessage(), e);
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getClass().getSimpleName() + ": " + safeMessage(e));
        body.put("correlationId", correlationId);
        return ResponseEntity.internalServerError().body(body);
    }

    private static String safeMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        // Clip any stupidly long messages (Firestore/Jackson can dump a huge chain).
        return msg.length() > 500 ? msg.substring(0, 500) + "… (truncated)" : msg;
    }
}
