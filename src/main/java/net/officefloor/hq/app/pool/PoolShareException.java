package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A share asked that is not a percent with at most two decimals, more than 0.00 and at most 100.00. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PoolShareException extends RuntimeException {
}
