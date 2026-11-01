package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period is open: it cannot be reopened. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodOpenException extends RuntimeException {}
