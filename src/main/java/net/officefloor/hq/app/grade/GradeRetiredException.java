package net.officefloor.hq.app.grade;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade asked of a retired grade: retiring it again, or giving it to someone. */
@ResponseStatus(HttpStatus.CONFLICT)
public class GradeRetiredException extends RuntimeException {
}
