package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A settle asked by a clerk of a claim whose due is above the sheet's clerk limit: a steward must settle it. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimOverLimitException extends RuntimeException {
}
