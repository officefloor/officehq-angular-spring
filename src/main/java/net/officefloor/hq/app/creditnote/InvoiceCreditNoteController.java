package net.officefloor.hq.app.creditnote;

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

/** The credit notes raised against an invoice, oldest first. */
@RestController
@RequestMapping("/api/projects/{projectId}/invoices/{invoiceId}/credit-notes")
public class InvoiceCreditNoteController {

    private final CreditNoteService service;

    public InvoiceCreditNoteController(CreditNoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<CreditNoteResponse> list(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.list(projectId, invoiceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreditNoteResponse issue(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody CreditNoteRequest request) {
        return service.issue(projectId, invoiceId, request);
    }
}
