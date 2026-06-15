package org.apiprivaterouter.javabackend.common.api;

import jakarta.validation.ConstraintViolationException;
import org.apiprivaterouter.javabackend.admin.account.service.MixedChannelConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, ex.getMessage()));
    }

    @ExceptionHandler(ApiErrorException.class)
    public ResponseEntity<Map<String, Object>> handleApiError(ApiErrorException ex) {
        log.warn("API error (status={}): {} - {}", ex.getStatus(), ex.getMessage(), ex.getReason());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", ex.getStatus());
        body.put("message", ex.getMessage());
        body.put("reason", ex.getReason());
        if (!ex.getMetadata().isEmpty()) {
            body.put("metadata", ex.getMetadata());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(AnthropicApiErrorException.class)
    public ResponseEntity<Map<String, Object>> handleAnthropicApiError(AnthropicApiErrorException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "type", "error",
                "error", Map.of(
                        "type", ex.getErrorType(),
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(OpenAiApiErrorException.class)
    public ResponseEntity<Map<String, Object>> handleOpenAiApiError(OpenAiApiErrorException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", Map.of(
                        "type", ex.getErrorType(),
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(StructuredApiErrorException.class)
    public ResponseEntity<Map<String, Object>> handleStructuredApiError(StructuredApiErrorException ex) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", ex.getStatus());
        body.put("message", ex.getMessage());
        body.put("reason", ex.getReason());
        if (!ex.getMetadata().isEmpty()) {
            body.put("metadata", ex.getMetadata());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    @ExceptionHandler(HttpStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpStatus(HttpStatusException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    @ExceptionHandler(MixedChannelConflictException.class)
    public ResponseEntity<Map<String, Object>> handleMixedChannelConflict(MixedChannelConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "mixed_channel_warning",
                "message", ex.getMessage(),
                "details", Map.of(
                        "group_id", ex.getGroupId(),
                        "group_name", ex.getGroupName(),
                        "current_platform", ex.getCurrentPlatform(),
                        "other_platform", ex.getOtherPlatform()
                )
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "Internal server error"));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
