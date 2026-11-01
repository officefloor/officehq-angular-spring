package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user's role may not close or reopen a period by the sheet's permission matrix. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodNotPermittedException extends RuntimeException {}
