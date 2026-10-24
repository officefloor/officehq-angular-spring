package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An open, settle, void or reassign asked by a user whose role the sheet's permission matrix does not
 * allow it, an approve or reject of a PENDING claim asked by a user who is not a steward of the sheet, or an
 * approve asked by the user who opened the claim. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimNotStewardException extends RuntimeException {
}
