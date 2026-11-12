package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An approver that cannot be named, such as a blank user name. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ApproverRefusedException extends RuntimeException {

    public ApproverRefusedException(String message) {
        super(message);
    }
}
