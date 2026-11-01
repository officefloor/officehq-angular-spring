package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The name is already a pool of the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatePoolNameException extends RuntimeException {}
