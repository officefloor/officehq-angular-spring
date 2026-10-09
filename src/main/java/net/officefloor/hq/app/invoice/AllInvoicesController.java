package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Invoices across every project, a page at a time. */
@RestController
@RequestMapping("/api/invoices")
public class AllInvoicesController {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 100;

    private final InvoiceService service;

    public AllInvoicesController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public InvoicePageResponse list(@RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return service.listAll(status, page, size);
    }
}
