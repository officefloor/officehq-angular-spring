package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit that types an amount other than its lines add up to on a claim that has lines. */
@ResponseStatus(HttpStatus.EXPECTATION_FAILED)
public class ClaimLinedAmountException extends RuntimeException {
}
