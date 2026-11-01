package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim can only be deferred to a date after the sheet's today. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimDeferDateException extends RuntimeException {}
