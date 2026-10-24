package net.officefloor.hq.app.locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A locale asked that is not one a user may choose. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class LocaleUnknownException extends RuntimeException {
}
