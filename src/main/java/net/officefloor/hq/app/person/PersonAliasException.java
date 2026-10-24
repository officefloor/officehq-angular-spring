package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An alias that is blank, or equals a name or alias already on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonAliasException extends RuntimeException {
}
