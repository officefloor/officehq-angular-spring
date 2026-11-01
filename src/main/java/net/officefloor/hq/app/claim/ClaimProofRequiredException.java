package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim whose due is above the proof threshold in force on its date cannot be settled or approved with no proofs. */
@ResponseStatus(HttpStatus.PRECONDITION_REQUIRED)
public class ClaimProofRequiredException extends RuntimeException {}
