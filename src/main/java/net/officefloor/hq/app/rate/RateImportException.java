package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A rate import with no lines, or with a line that is not {@code grade,rate,from,to} as the rate form
 * takes them. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RateImportException extends RuntimeException {
}
