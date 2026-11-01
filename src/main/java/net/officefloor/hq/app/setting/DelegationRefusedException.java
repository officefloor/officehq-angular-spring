package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can delegate the steward role, and only for a to-date on or after the from-date. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class DelegationRefusedException extends RuntimeException {}
