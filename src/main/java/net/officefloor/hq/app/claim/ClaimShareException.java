package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A share of a claim refused: with its own person or a person already holding one, a share not above 0.00 or taking
 * the claim's shares above 100.00, or a claim SETTLED, VOID or DELETED.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimShareException extends RuntimeException {

    public ClaimShareException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
