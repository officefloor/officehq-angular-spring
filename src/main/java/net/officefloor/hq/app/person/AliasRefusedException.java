package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An alias a person cannot be given: a blank one, or one equal to a name or alias already on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class AliasRefusedException extends RuntimeException {

    public AliasRefusedException(String message) {
        super(message);
    }
}
