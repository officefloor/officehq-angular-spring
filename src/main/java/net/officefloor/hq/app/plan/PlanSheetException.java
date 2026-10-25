package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A plan asked to make its claims on a sheet that is not one of its sheet's descendants. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class PlanSheetException extends RuntimeException {
}
