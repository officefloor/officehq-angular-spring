package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can approve or reject a PENDING claim. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimNotStewardException extends RuntimeException {}
