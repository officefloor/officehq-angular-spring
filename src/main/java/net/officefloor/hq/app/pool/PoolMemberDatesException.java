package net.officefloor.hq.app.pool;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A membership's to-date is before its from-date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PoolMemberDatesException extends RuntimeException {}
