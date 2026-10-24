package net.officefloor.hq.app.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change asked from a screen showing its sheet as of a date: such a screen may change nothing. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AsOfRefusedException extends RuntimeException {
}
