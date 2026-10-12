package net.officefloor.hq.app.invoices;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Turns a refused invoice change into a problem response whose detail says what is wrong. */
@RestControllerAdvice(assignableTypes = InvoiceController.class)
public class InvoiceErrors {

    private static final List<String> FIELD_ORDER = List.of("unitType", "description", "qty", "unitPrice", "discountPct", "discountAmount", "discountCap", "discountReason", "discountGiven", "amount", "date", "reference", "dueDate", "instalments", "instalmentRate");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .sorted((a, b) -> Integer.compare(FIELD_ORDER.indexOf(a.getField()),
                        FIELD_ORDER.indexOf(b.getField())))
                .map(f -> f.getDefaultMessage())
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse("The change is not valid.");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Enter the numbers as numbers, like 1.5 and 12.35.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason());
    }

    @ExceptionHandler(InvoiceRejectedException.class)
    public ProblemDetail rejected(InvoiceRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
