package net.officefloor.hq.app.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change asked of a sheet by a user who is a viewer on it: a viewer may change nothing. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ViewerRefusedException extends RuntimeException {
}
