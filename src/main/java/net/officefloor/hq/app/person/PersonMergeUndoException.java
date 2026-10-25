package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An undo of a merge refused: the person has no merge to undo (their last merge into them is undone
 * already), it is asked by a user other than the steward who made it, or on a later sheet's today than
 * it was made on, or the merged person's name is taken again. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMergeUndoException extends RuntimeException {
}
