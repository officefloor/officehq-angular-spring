package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pool's name cannot be blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PoolNameBlankException extends RuntimeException {}
