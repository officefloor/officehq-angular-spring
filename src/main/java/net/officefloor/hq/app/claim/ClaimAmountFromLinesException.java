package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit typing another amount for a claim with lines: its amount is its lines added up. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimAmountFromLinesException extends RuntimeException {

    public ClaimAmountFromLinesException(Long id) {
        super("Claim " + id + " has lines: its amount is their quantities times their unit amounts added up");
    }
}
