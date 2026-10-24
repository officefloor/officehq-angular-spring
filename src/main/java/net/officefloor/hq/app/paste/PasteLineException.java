package net.officefloor.hq.app.paste;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A paste with no lines, or with a line that is not {@code name,amount,date,note} as the claim form
 * takes them. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PasteLineException extends RuntimeException {
}
