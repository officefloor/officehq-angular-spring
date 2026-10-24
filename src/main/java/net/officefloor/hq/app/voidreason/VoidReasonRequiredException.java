package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void of a claim on a sheet with void reasons that names none of them. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class VoidReasonRequiredException extends RuntimeException {
}
