package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A transfer refused: not by a steward, of a retired person, to the same sheet, to an archived sheet, matching two
 * people or a retired one there, or with a name used elsewhere in its tree or a contact the other sheet has already.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonTransferRefusedException extends RuntimeException {

    public PersonTransferRefusedException(String message) {
        super(message);
    }
}
