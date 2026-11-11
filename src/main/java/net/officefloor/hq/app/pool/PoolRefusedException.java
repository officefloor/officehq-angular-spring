package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool, or a member of one, that may not be added. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PoolRefusedException extends RuntimeException {

    public PoolRefusedException(String message) {
        super(message);
    }
}
