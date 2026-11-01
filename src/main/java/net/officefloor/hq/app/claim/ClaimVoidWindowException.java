package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A SETTLED claim can be voided only within 7 days of its settlement by the sheet's today. */
@ResponseStatus(HttpStatus.GONE)
public class ClaimVoidWindowException extends RuntimeException {}
