package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The person is already in the pool. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatePoolMemberException extends RuntimeException {}
