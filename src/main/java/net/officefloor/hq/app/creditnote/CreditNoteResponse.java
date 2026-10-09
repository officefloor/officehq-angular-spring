package net.officefloor.hq.app.creditnote;

import java.math.BigDecimal;
import java.time.Instant;

public record CreditNoteResponse(Long id, Long invoiceId, BigDecimal amount, Instant issuedAt) {

    static CreditNoteResponse from(CreditNote note) {
        return new CreditNoteResponse(note.getId(), note.getInvoiceId(), note.getAmount(), note.getIssuedAt());
    }
}
