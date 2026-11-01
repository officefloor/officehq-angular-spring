package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The claim was already reminded on the sheet's today; it can be reminded once per day. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRemindedException extends RuntimeException {}
