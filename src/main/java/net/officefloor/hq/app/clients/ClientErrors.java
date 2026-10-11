package net.officefloor.hq.app.clients;

import java.util.Comparator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns a refused client into a problem response whose detail says what is wrong. */
@RestControllerAdvice(assignableTypes = ClientController.class)
public class ClientErrors {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(f -> f.getField().equals("name") ? 0 : 1))
                .map(f -> f.getDefaultMessage())
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse("The client is not valid.");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ClientRejectedException.class)
    public ProblemDetail rejected(ClientRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
