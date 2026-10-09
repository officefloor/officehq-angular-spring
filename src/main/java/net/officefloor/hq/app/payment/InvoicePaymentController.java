package net.officefloor.hq.app.payment;

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

/** The payments a client has made against an invoice, oldest first. */
@RestController
@RequestMapping("/api/projects/{projectId}/invoices/{invoiceId}/payments")
public class InvoicePaymentController {

    private final PaymentService service;

    public InvoicePaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentResponse> list(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.list(projectId, invoiceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse record(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody PaymentRequest request) {
        return service.record(projectId, invoiceId, request);
    }
}
