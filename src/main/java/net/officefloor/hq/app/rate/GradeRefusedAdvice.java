package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A refused grade, wherever it was chosen, answers 409 with the reason, for the screen to show. */
@RestControllerAdvice
public class GradeRefusedAdvice {

    public record Refusal(String message) {
    }

    @ExceptionHandler(GradeRefused.class)
    public ResponseEntity<Refusal> refused(GradeRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getReason()));
    }
}
