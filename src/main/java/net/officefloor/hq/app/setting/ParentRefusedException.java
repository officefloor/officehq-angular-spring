package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A parent sheet that is the sheet itself, has the sheet as an ancestor, or has never been loaded. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ParentRefusedException extends RuntimeException {

    public ParentRefusedException(String sheetId, String parent, String reason) {
        super("Sheet " + parent + " cannot be the parent of sheet " + sheetId + ": " + reason);
    }
}
