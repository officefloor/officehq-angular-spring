package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An opening balance paste with no lines, or with a line that is not {@code name,period,amount} for a
 * person and a period on the sheet and an amount of 0.00 or more with two decimals. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OpeningImportException extends RuntimeException {
}
