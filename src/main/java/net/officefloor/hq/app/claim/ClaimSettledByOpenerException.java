package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user who opened a claim cannot settle it; someone else must. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimSettledByOpenerException extends RuntimeException {}
