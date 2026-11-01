package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** While the sheet's list of void reasons is not empty, a claim is voided only with a reason on it. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class VoidReasonRequiredException extends RuntimeException {}
