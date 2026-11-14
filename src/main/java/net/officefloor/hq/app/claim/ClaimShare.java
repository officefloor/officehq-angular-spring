package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** A share of a claim another person on the sheet holds: the holder, their share percent (two
 *  decimals) and the sheet's today when it was shared. */
public record ClaimShare(Long personId, BigDecimal share, LocalDate on) {

    /** The holder's part of a due: the due times their share divided by 100, rounded half to even. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    String text() {
        return personId + ":" + share.toPlainString() + ":" + on;
    }

    static ClaimShare parse(String text) {
        String[] parts = text.split(":");
        return new ClaimShare(Long.valueOf(parts[0]), new BigDecimal(parts[1]), LocalDate.parse(parts[2]));
    }
}
