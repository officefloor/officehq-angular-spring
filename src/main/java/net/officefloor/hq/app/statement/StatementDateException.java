package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A statement cannot be taken as of a date after the sheet's today, nor before the person was on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementDateException extends RuntimeException {}
