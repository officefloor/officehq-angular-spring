package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A plan that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(String sheetId, Long id) {
        super("Plan " + id + " not found on sheet " + sheetId);
    }
}
