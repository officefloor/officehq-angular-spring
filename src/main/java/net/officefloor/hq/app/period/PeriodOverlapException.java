package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period's dates overlap another period's on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodOverlapException extends RuntimeException {}
