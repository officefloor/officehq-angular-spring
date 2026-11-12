package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Someone whose role the permission matrix does not allow tried to change the sheet's today. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class TodayForbiddenException extends RuntimeException {

    public TodayForbiddenException(String sheetId, String user) {
        super(user + " may not change the today of sheet " + sheetId);
    }
}
