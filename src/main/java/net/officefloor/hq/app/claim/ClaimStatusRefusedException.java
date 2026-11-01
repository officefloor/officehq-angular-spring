package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The claim's status cannot change that way (a SETTLED or VOID claim cannot change at all). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimStatusRefusedException extends RuntimeException {}
