package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No such pool on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PoolNotFoundException extends RuntimeException {}
