package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An open of a claim whose due is above the note threshold in force on its date, while its note is
 * empty. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimNoteRequiredException extends RuntimeException {
}
