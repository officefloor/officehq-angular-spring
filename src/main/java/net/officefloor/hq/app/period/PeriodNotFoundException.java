package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An action on a period that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PeriodNotFoundException extends RuntimeException {
}
