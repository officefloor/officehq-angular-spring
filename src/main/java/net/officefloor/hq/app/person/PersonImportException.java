package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An import with no lines, or with a line the person form would refuse: not {@code name,grade,terms,contact}
 * as the form takes them, a name taken on another sheet of the sheet's tree, a contact another person on
 * the sheet has, or a name, person or contact an earlier line of the paste has. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PersonImportException extends RuntimeException {
}
