package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user's role may not take the action (open, settle, void, reassign) by the sheet's permission matrix. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimNotPermittedException extends RuntimeException {}
