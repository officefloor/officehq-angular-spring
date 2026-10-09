package net.officefloor.hq.app.invoice;

import java.util.List;
import org.springframework.data.domain.Page;

/** One page of the invoices listed across all projects, with where it sits among the rest. */
public record InvoicePageResponse(List<InvoiceSummaryResponse> items, int page, int size, long totalItems,
        int totalPages) {

    static InvoicePageResponse from(Page<Invoice> page) {
        return new InvoicePageResponse(page.map(InvoiceSummaryResponse::from).getContent(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
