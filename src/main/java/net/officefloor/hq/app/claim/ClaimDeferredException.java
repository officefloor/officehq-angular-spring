package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A deferred claim cannot be settled until the sheet's today reaches its deferred-until date. */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class ClaimDeferredException extends RuntimeException {}
