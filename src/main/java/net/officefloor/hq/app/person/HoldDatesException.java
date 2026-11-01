package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The hold's to-date is before its from-date. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class HoldDatesException extends RuntimeException {}
