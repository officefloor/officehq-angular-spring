package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void reason asked that is already on the sheet's list. */
@ResponseStatus(HttpStatus.CONFLICT)
public class VoidReasonTakenException extends RuntimeException {
}
