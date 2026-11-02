package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only the steward who made the last merge into a person can undo it, and only on the sheet-today it was made. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMergeUndoRefusedException extends RuntimeException {}
