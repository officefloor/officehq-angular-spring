package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change was asked of an archived sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetArchivedException extends RuntimeException {

    public SheetArchivedException(String sheetId) {
        super("Sheet " + sheetId + " is archived and may not be changed");
    }
}
