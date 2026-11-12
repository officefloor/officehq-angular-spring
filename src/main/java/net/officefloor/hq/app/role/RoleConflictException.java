package net.officefloor.hq.app.role;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another user set the same name's first role at the same moment. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RoleConflictException extends RuntimeException {

    public RoleConflictException() {
        super("The role was changed at the same moment; try again.");
    }
}
