package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class PeriodNotFoundException extends RuntimeException {

    public PeriodNotFoundException(String sheetId, String name) {
        super("No period " + name + " on sheet " + sheetId);
    }
}
