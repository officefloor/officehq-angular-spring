package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A fiscal year with no periods on the sheet cannot be rolled over. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RolloverRefusedException extends RuntimeException {}
