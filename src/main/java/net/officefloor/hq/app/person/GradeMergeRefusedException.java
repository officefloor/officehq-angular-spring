package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can merge a grade into another, and not into itself. */
@ResponseStatus(HttpStatus.CONFLICT)
public class GradeMergeRefusedException extends RuntimeException {}
