package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim cannot be settled as of a date after the sheet's today, nor as of one before it existed. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimSettleDateException extends RuntimeException {}
