package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

/**
 * One dated entry on a client's running account. An invoice is a charge; a payment, credit note or deposit is a
 * credit; a refund of unused credit is a charge again. The balance is what the client owes once this entry and every
 * one before it is counted (negative when the client is in credit). The invoice is the one the entry is for, if any.
 */
public record StatementEntry(Kind kind, Long sourceId, Long invoiceId, LocalDate date, String description,
        BigDecimal charge, BigDecimal credit, BigDecimal balance) {

    /** What an entry records, in the order entries on the same day are listed. */
    public enum Kind {
        INVOICE, PAYMENT, CREDIT_NOTE, DEPOSIT, REFUND
    }

    /** Date order; on the same day charges come before what was paid against them, then by record id. */
    static final Comparator<StatementEntry> DATE_ORDER = Comparator.comparing(StatementEntry::date)
            .thenComparing(StatementEntry::kind)
            .thenComparing(StatementEntry::sourceId);

    static StatementEntry charge(Kind kind, Long sourceId, Long invoiceId, LocalDate date, String description,
            BigDecimal amount) {
        return new StatementEntry(kind, sourceId, invoiceId, date, description, amount.setScale(2), null, null);
    }

    static StatementEntry credit(Kind kind, Long sourceId, Long invoiceId, LocalDate date, String description,
            BigDecimal amount) {
        return new StatementEntry(kind, sourceId, invoiceId, date, description, null, amount.setScale(2), null);
    }

    /** What this entry adds to what the client owes. */
    BigDecimal change() {
        return charge != null ? charge : credit.negate();
    }

    StatementEntry withBalance(BigDecimal balance) {
        return new StatementEntry(kind, sourceId, invoiceId, date, description, charge, credit, balance);
    }
}
