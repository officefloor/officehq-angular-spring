package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool asked as a member of a pool that it contains, through any chain, or of itself. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolCycleException extends RuntimeException {
}
