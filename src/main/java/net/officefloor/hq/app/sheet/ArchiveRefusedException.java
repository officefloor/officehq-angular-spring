package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Archiving the sheet was refused: only a steward may, and only a sheet not archived already. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ArchiveRefusedException extends RuntimeException {

    public ArchiveRefusedException(String message) {
        super(message);
    }
}
