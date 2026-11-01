package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim cannot be moved to the person it is already theirs. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ClaimReassignRefusedException extends RuntimeException {}
