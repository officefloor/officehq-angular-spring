package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A membership's dates asked that are not dates, or a to-date before the from-date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PoolDatesException extends RuntimeException {
}
