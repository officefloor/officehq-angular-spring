package net.officefloor.hq.app.invoice;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @PutMapping("/{invoiceId}/line-items/{lineItemId}")
    public InvoiceDetailResponse updateLineItem(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @PathVariable Long lineItemId, @Valid @RequestBody LineItemRequest request) {
        return service.updateLineItem(projectId, invoiceId, lineItemId, request);
    }

    @DeleteMapping("/{invoiceId}/line-items/{lineItemId}")
    public InvoiceDetailResponse removeLineItem(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @PathVariable Long lineItemId) {
        return service.removeLineItem(projectId, invoiceId, lineItemId);
    }

    @PutMapping("/{invoiceId}/discount")
    public InvoiceDetailResponse applyDiscount(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody DiscountRequest request) {
        return service.applyDiscount(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/tax")
    public InvoiceDetailResponse applyTax(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody TaxRequest request) {
        return service.applyTax(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/surcharge")
    public InvoiceDetailResponse applySurcharge(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody SurchargeRequest request) {
        return service.applySurcharge(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/minimum-charge")
    public InvoiceDetailResponse applyMinimumCharge(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody MinimumChargeRequest request) {
        return service.applyMinimumCharge(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/early-payment")
    public InvoiceDetailResponse applyEarlyPayment(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody EarlyPaymentRequest request) {
        return service.applyEarlyPayment(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/rebate")
    public InvoiceDetailResponse applyRebate(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody RebateRequest request) {
        return service.applyRebate(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/late-fee")
    public InvoiceDetailResponse applyLateFee(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody LateFeeRequest request) {
        return service.applyLateFee(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/retention")
    public InvoiceDetailResponse applyRetention(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody RetentionRequest request) {
        return service.applyRetention(projectId, invoiceId, request);
    }

    @PutMapping("/{invoiceId}/po-number")
    public InvoiceDetailResponse setPoNumber(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody PoNumberRequest request) {
        return service.setPoNumber(projectId, invoiceId, request.poNumber());
    }

    @PostMapping("/{invoiceId}/retention/release")
    public InvoiceDetailResponse releaseRetention(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.releaseRetention(projectId, invoiceId);
    }

    @PostMapping("/send-drafts")
    public List<InvoiceResponse> sendDrafts(@PathVariable Long projectId) {
        return service.sendDrafts(projectId);
    }

    @PostMapping("/{invoiceId}/send")
    public InvoiceResponse send(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.send(projectId, invoiceId);
    }

    @PostMapping("/{invoiceId}/write-off")
    public InvoiceDetailResponse writeOff(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.writeOff(projectId, invoiceId);
    }

    @PostMapping("/{invoiceId}/write-off-part")
    public InvoiceDetailResponse writeOffPart(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody PartialWriteOffRequest request) {
        return service.writeOffPart(projectId, invoiceId, request);
    }

    @PostMapping("/{invoiceId}/dispute")
    public InvoiceResponse dispute(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.dispute(projectId, invoiceId);
    }

    @PostMapping("/{invoiceId}/cancel")
    public InvoiceResponse cancel(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.cancel(projectId, invoiceId);
    }
}
