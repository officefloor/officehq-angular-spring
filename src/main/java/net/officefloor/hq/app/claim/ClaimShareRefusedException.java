package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A share must go to another person of the sheet who holds none of the claim yet, and keep the claim's
 * shares at most 100.00 in total.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimShareRefusedException extends RuntimeException {}
