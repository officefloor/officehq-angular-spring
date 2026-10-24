package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A transfer refused: asked by a user who is not a steward of the sheet, of a retired person, to the
 * sheet's own id or to an archived sheet, or when the person's name, one of their aliases or their contact
 * is already on the other sheet; or an undo of a transfer
 * refused: by a user who is not a steward, of a person who did not arrive by transfer, or on a later day. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonTransferException extends RuntimeException {
}
