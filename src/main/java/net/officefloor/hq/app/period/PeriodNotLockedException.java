package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period is not locked, so it cannot be unlocked. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodNotLockedException extends RuntimeException {}
