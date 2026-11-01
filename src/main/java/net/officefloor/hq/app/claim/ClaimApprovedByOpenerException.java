package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user who opened a claim cannot approve it; another steward must. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimApprovedByOpenerException extends RuntimeException {}
