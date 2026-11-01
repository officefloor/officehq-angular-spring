package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The pool cannot be a member here: it is already a member of a pool, it would contain itself through
 * some chain, or the chain of pools would be more than three deep.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class PoolNestingException extends RuntimeException {}
