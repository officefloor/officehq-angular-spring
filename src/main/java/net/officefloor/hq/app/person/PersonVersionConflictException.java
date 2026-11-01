package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The person was saved by someone else since the version the edit was made from. */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class PersonVersionConflictException extends RuntimeException {}
