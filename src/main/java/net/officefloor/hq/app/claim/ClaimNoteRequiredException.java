package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim whose due is above the note threshold in force on its date cannot be opened with an empty note. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimNoteRequiredException extends RuntimeException {}
