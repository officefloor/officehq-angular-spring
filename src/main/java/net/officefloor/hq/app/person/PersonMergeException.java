package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A merge refused: asked by a user who is not a steward of the sheet, of a person into themselves, or
 * when either person is retired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMergeException extends RuntimeException {
}
