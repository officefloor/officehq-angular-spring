package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clerk cannot settle a claim whose due is above the sheet's clerk limit; a steward can. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimOverClerkLimitException extends RuntimeException {}
