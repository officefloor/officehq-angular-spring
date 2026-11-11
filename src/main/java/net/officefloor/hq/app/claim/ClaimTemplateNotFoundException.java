package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No claim template of that name on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClaimTemplateNotFoundException extends RuntimeException {

    public ClaimTemplateNotFoundException(String sheetId, String name) {
        super("No claim template " + name + " on sheet " + sheetId);
    }
}
