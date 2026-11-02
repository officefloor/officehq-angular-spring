package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The period is locked: it cannot be locked again, nor reopened until unlocked. */
@ResponseStatus(HttpStatus.LOCKED)
public class PeriodLockedException extends RuntimeException {}
