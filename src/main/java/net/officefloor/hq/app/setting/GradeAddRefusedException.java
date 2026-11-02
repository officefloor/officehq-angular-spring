package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The grade cannot be added (not one to three capital letters, or a grade already), or is not a grade of the sheet. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class GradeAddRefusedException extends RuntimeException {}
