package net.officefloor.hq.app.template;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim template saved with a blank name. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ClaimTemplateBlankException extends RuntimeException {
}
