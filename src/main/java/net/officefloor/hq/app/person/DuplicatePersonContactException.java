package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another person on the sheet already has that contact. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicatePersonContactException extends RuntimeException {}
