package com.firewalldo.portal;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

final class ApiError extends RuntimeException {
    final int status;
    final String code;

    ApiError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ApiError invalid(String message) { return new ApiError(400, "INVALID_INPUT", message); }
    static ApiError forbidden() { return new ApiError(403, "FORBIDDEN", "Você não tem permissão para esta ação."); }
    static ApiError missing(String message) { return new ApiError(404, "NOT_FOUND", message); }
    static ApiError conflict(String message) { return new ApiError(409, "CONFLICT", message); }
    static ApiError unauthenticated() { return new ApiError(401, "UNAUTHENTICATED", "Entre na sua conta para continuar."); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, String>> handle(ApiError error) {
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> badJson() {
        return handle(ApiError.invalid("Envie um JSON válido."));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate() {
        return handle(ApiError.conflict("Já existe um registro com esses dados."));
    }
}
