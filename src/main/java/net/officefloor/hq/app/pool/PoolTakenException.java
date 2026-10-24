package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool name asked that is already on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolTakenException extends RuntimeException {
}
