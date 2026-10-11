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

    /** What has been paid off an instalment, what is still unpaid on it, and its status on a day. */
    public record Progress(Instalment instalment, BigDecimal paid, BigDecimal unpaid, Status status) {
    }
}
