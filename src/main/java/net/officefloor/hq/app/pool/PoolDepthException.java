package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool asked as a member that would make a chain of pools more than three deep. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolDepthException extends RuntimeException {
}
