package net.officefloor.hq.app.template;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim asked from a template that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClaimTemplateNotFoundException extends RuntimeException {
}
