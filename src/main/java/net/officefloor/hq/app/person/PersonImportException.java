package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An import with no lines, or with a line the person form would refuse: not {@code name,grade,terms,contact}
 * as the form takes them, or a name or contact taken on the sheet, on another sheet of its tree, or by an
 * earlier line of the paste. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PersonImportException extends RuntimeException {
}
