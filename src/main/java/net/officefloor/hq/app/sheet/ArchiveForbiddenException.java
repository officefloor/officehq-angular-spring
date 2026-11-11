package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Someone other than a steward tried to archive the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ArchiveForbiddenException extends RuntimeException {

    public ArchiveForbiddenException(String sheetId, String user) {
        super(user + " is not a steward on sheet " + sheetId + " and may not archive it");
    }
}
