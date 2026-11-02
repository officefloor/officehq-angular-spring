package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can withdraw a rate, and only one there is. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RateWithdrawRefusedException extends RuntimeException {}
