package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The date is already on the sheet's list of holidays. */
@ResponseStatus(HttpStatus.CONFLICT)
public class HolidayTakenException extends RuntimeException {

    public HolidayTakenException(String sheetId, LocalDate date) {
        super(date + " is already a holiday on sheet " + sheetId);
    }
}
