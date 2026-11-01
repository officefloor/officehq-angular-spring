package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period is closed: it cannot be closed again, and no claim dated inside it can change. */
@ResponseStatus(HttpStatus.LOCKED)
public class PeriodClosedException extends RuntimeException {}
