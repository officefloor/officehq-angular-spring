package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void reason asked that is blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class VoidReasonBlankException extends RuntimeException {
}
