package net.officefloor.hq.app.adjustmentnote;

import java.math.BigDecimal;
import java.time.Instant;

public record AdjustmentNoteResponse(Long id, Long invoiceId, BigDecimal amount, String reason, Instant issuedAt) {

    static AdjustmentNoteResponse from(AdjustmentNote note) {
        return new AdjustmentNoteResponse(note.getId(), note.getInvoiceId(), note.getAmount(), note.getReason(),
                note.getIssuedAt());
    }
}
