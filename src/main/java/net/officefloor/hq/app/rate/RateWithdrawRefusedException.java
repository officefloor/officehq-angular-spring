package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A withdrawal of a rate by anyone but a steward. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RateWithdrawRefusedException extends RuntimeException {

    public RateWithdrawRefusedException() {
        super("Only a steward can withdraw a rate.");
    }
}
