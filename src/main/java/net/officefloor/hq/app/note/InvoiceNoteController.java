package net.officefloor.hq.app.note;

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

/** The notes written on an invoice, newest first. */
@RestController
@RequestMapping("/api/projects/{projectId}/invoices/{invoiceId}/notes")
public class InvoiceNoteController {

    private final NoteService service;

    public InvoiceNoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoteResponse> list(@PathVariable Long projectId, @PathVariable Long invoiceId) {
        return service.listForInvoice(projectId, invoiceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse create(@PathVariable Long projectId, @PathVariable Long invoiceId,
            @Valid @RequestBody NoteRequest request) {
        return service.createForInvoice(projectId, invoiceId, request);
    }
}
