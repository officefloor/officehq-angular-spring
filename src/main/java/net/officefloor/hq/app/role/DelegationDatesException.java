package net.officefloor.hq.app.role;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A delegation's to-date is before its from-date. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class DelegationDatesException extends RuntimeException {

    public DelegationDatesException(LocalDate from, LocalDate to) {
        super("A delegation to " + to + " ends before it starts from " + from);
    }
}
