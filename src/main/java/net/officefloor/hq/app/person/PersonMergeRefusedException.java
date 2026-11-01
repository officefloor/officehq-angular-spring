package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can merge a person into another: not into themselves, and neither of them retired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMergeRefusedException extends RuntimeException {}
