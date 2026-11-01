package net.officefloor.hq.app.holiday;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The date is already on the sheet's list of holidays. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateHolidayException extends RuntimeException {}
