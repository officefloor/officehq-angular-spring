package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstalmentResponse(Long id, Long invoiceId, BigDecimal amount, LocalDate date, boolean paid) {

    static InstalmentResponse from(Instalment instalment) {
        return new InstalmentResponse(instalment.getId(), instalment.getInvoiceId(), instalment.getAmount(),
                instalment.getDueDate(), instalment.isPaid());
    }
}
