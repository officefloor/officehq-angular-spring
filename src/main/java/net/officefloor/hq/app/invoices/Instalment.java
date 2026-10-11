package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One instalment of an invoice's plan: its number (from 1), the day it falls due, what is paid, the
 * interest and principal it is made of, and the balance still owed after it; money to the cent.
 */
public record Instalment(int number, LocalDate dueDate, BigDecimal payment, BigDecimal interest,
        BigDecimal principal, BigDecimal balance) {
}
