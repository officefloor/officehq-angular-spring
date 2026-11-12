package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The withdrawal of a rate by a user who is not a steward on the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RateWithdrawForbiddenException extends RuntimeException {

    public RateWithdrawForbiddenException(String grade, String user) {
        super("The rate for grade " + grade + " cannot be withdrawn by " + user + ": only a steward can");
    }
}
