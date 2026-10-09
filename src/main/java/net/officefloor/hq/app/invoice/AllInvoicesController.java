package net.officefloor.hq.app.invoice;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invoices across every project. */
@RestController
@RequestMapping("/api/invoices")
public class AllInvoicesController {

    private final InvoiceService service;

    public AllInvoicesController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvoiceSummaryResponse> list() {
        return service.listAll();
    }
}
