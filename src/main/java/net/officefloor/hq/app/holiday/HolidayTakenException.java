package net.officefloor.hq.app.holiday;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A holiday asked for a date already on the sheet's list. */
@ResponseStatus(HttpStatus.CONFLICT)
public class HolidayTakenException extends RuntimeException {
}
