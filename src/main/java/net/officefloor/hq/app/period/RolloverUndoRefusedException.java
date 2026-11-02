package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The last rollover cannot be undone: there is none, it was made on another sheet-today or by someone else,
 * or a claim now belongs to a period it generated.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class RolloverUndoRefusedException extends RuntimeException {}
