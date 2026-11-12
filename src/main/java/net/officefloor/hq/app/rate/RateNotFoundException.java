package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The grade has no rate of its own on the sheet with the from-date. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RateNotFoundException extends RuntimeException {

    public RateNotFoundException(String grade, LocalDate fromDate) {
        super("Grade " + grade + " has no rate " + (fromDate == null ? "from the beginning of time" : "from " + fromDate));
    }
}
