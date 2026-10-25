package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade merge refused: asked by a user who is not a steward of the sheet, of a grade into itself, or
 * into a retired grade. */
@ResponseStatus(HttpStatus.CONFLICT)
public class GradeMergeException extends RuntimeException {
}
