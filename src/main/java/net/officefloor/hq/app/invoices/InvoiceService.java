package net.officefloor.hq.app.invoices;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists a client's invoices. */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;

    public InvoiceService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public List<Invoice> forClient(Long clientId) {
        return invoices.findByClientIdOrderByIdAsc(clientId);
    }
}
