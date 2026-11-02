package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The grade is retired: it cannot be retired again, nor chosen for a new person or in an edit. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class GradeRetiredException extends RuntimeException {}
