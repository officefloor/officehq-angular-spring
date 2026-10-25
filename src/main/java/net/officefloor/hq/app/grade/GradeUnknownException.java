package net.officefloor.hq.app.grade;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade that is not one of the sheet's grades, or a grade added that already is one or is not a code
 * of one to three capital letters. */
@ResponseStatus(HttpStatus.CONFLICT)
public class GradeUnknownException extends RuntimeException {
}
