package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool asked as a member that is already a member of a pool. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolNestedException extends RuntimeException {
}
