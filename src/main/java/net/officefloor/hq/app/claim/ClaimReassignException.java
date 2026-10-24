package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A move of a claim that cannot move: one that is SETTLED or VOID, a correction (it corrects a claim
 * of its own person), or a move to the person it already belongs to. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimReassignException extends RuntimeException {
}
