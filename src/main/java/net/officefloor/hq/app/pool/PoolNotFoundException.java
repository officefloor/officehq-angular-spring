package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool asked that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PoolNotFoundException extends RuntimeException {
}
