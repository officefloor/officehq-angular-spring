package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The plan is already paused (when pausing) or is running (when resuming). */
@ResponseStatus(HttpStatus.CONFLICT)
public class PlanPauseRefusedException extends RuntimeException {}
