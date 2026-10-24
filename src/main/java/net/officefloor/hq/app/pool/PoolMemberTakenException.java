package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A member asked of a pool that the person is already in. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolMemberTakenException extends RuntimeException {
}
