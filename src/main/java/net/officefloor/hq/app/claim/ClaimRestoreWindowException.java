package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A DELETED claim can be restored only on the same sheet's today it was deleted. */
@ResponseStatus(HttpStatus.GONE)
public class ClaimRestoreWindowException extends RuntimeException {}
