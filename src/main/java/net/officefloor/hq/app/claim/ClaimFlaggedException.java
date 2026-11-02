package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** While its person's flag is in force, only a steward can open a claim of theirs. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimFlaggedException extends RuntimeException {}
