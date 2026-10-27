package com.splitbill.adapters.input.rest;

import com.splitbill.domain.exception.DomainException;
import jakarta.validation.ConstraintViolation;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String GENERIC_VALIDATION_MESSAGE = "Preencha todos os campos obrigatorios.";

    @ExceptionHandler(DomainException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleDomainException(DomainException exception) {
        return new ErrorResponse(exception.getMessage(), List.of());
    }

    /**
     * O app mostra o {@code message} direto pro usuario: antes saia
     * "Validation failed" em ingles. Quando a anotacao tem mensagem propria
     * (ex.: {@code @NotBlank(message = "Informe o nome do evento")}), usa
     * ela; senao, uma mensagem generica em portugues.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidationException(MethodArgumentNotValidException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
        List<String> details = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        String message = fieldErrors.stream()
                .filter(ApiExceptionHandler::hasCustomMessage)
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse(GENERIC_VALIDATION_MESSAGE);
        return new ErrorResponse(message, details);
    }

    private static boolean hasCustomMessage(FieldError error) {
        if (!error.contains(ConstraintViolation.class)) {
            return false;
        }
        String template = error.unwrap(ConstraintViolation.class).getMessageTemplate();
        return template != null && !template.startsWith("{");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDeniedException(AccessDeniedException exception) {
        return new ErrorResponse(exception.getMessage(), List.of());
    }

    public record ErrorResponse(String message, List<String> details) {
    }
}
