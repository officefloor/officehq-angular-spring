package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The plan's end date is before its start date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PlanRefusedException extends RuntimeException {}
