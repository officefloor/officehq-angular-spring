package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool name asked that is blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PoolBlankException extends RuntimeException {
}
