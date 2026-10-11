package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One instalment of an invoice's plan: its number (from 1), the day it falls due, what is paid, the
 * interest and principal it is made of, and the balance still owed after it; money to the cent.
 */
public record Instalment(int number, LocalDate dueDate, BigDecimal payment, BigDecimal interest,
        BigDecimal principal, BigDecimal balance) {

    /** Where an instalment is: paid off, part paid, overdue (more than the grace period past due) or due. */
    public enum Status {
        PAID, PART_PAID, OVERDUE, DUE
    }

    /**
     * What has been paid off an instalment, what is still unpaid on it, the late fee and late interest
     * it has been charged and what of each is still unpaid, and its status on a day.
     */
    public record Progress(Instalment instalment, BigDecimal paid, BigDecimal unpaid, BigDecimal lateFee,
            BigDecimal feeUnpaid, BigDecimal lateInterest, BigDecimal interestUnpaid, Status status) {

        /** All still owed on the instalment: what is unpaid on it, its late fee and its late interest. */
        public BigDecimal owed() {
            return unpaid.add(feeUnpaid).add(interestUnpaid);
        }

        /** What is still unpaid of the late fee and late interest the instalment has been charged. */
        public BigDecimal chargesUnpaid() {
            return feeUnpaid.add(interestUnpaid);
        }
    }
}
