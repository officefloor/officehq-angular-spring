package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The claim's status cannot change that way (a VOID claim cannot change at all), or the
 * claim cannot be edited (only a DRAFT claim can).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimStatusRefusedException extends RuntimeException {}
