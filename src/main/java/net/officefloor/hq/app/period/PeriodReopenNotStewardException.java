package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can reopen a closed period. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodReopenNotStewardException extends RuntimeException {}
