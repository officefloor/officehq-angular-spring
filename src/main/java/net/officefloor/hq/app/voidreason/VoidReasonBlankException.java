package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void reason cannot be blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class VoidReasonBlankException extends RuntimeException {}
