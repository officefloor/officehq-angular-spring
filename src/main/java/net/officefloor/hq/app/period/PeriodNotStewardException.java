package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A close or reopen asked by a user whose role the sheet's permission matrix does not allow it. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodNotStewardException extends RuntimeException {
}
