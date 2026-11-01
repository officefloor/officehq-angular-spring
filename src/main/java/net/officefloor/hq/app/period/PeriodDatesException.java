package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period's to-date is before its from-date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PeriodDatesException extends RuntimeException {}
