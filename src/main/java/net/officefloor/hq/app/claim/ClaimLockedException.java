package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another user's open editor locks the claim: it cannot be edited until they save or cancel. */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class ClaimLockedException extends RuntimeException {}
