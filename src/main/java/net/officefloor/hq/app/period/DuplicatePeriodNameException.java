package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another period on the sheet already has that name. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatePeriodNameException extends RuntimeException {}
