package net.officefloor.hq.app.invoice;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvoiceResponse> list(@PathVariable Long projectId) {
        return service.listForProject(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@PathVariable Long projectId, @Valid @RequestBody InvoiceRequest request) {
        return service.create(projectId, request);
    }

    @GetMapping("/{invoiceId}")
    public InvoiceDetailResponse get(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.get(projectId, invoiceId);
    }

    @PostMapping("/{invoiceId}/line-items")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetailResponse addLineItem(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody LineItemRequest request) {
        return service.addLineItem(projectId, invoiceId, request);
    }

    @PostMapping("/{invoiceId}/send")
    public InvoiceResponse send(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.send(projectId, invoiceId);
    }

    @PostMapping("/{invoiceId}/pay")
    public InvoiceResponse pay(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.pay(projectId, invoiceId);
    }
}
