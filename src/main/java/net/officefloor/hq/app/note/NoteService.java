package net.officefloor.hq.app.note;

import java.time.Instant;
import java.util.List;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {

    private final NoteRepository notes;
    private final ProjectRepository projects;
    private final InvoiceRepository invoices;

    public NoteService(NoteRepository notes, ProjectRepository projects, InvoiceRepository invoices) {
        this.notes = notes;
        this.projects = projects;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> listForProject(Long projectId) {
        requireProject(projectId);
        return notes.findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(Note.PROJECT, projectId).stream()
                .map(NoteResponse::from).toList();
    }

    @Transactional
    public NoteResponse createForProject(Long projectId, NoteRequest request) {
        requireProject(projectId);
        Note saved = notes.save(new Note(Note.PROJECT, projectId, request.text().trim(), Instant.now()));
        return NoteResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> listForInvoice(Long projectId, Long invoiceId) {
        requireInvoice(projectId, invoiceId);
        return notes.findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(Note.INVOICE, invoiceId).stream()
                .map(NoteResponse::from).toList();
    }

    @Transactional
    public NoteResponse createForInvoice(Long projectId, Long invoiceId, NoteRequest request) {
        requireInvoice(projectId, invoiceId);
        Note saved = notes.save(new Note(Note.INVOICE, invoiceId, request.text().trim(), Instant.now()));
        return NoteResponse.from(saved);
    }

    private void requireInvoice(Long projectId, Long invoiceId) {
        boolean onProject = invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .isPresent();
        if (!onProject) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice");
        }
    }

    private void requireProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job");
        }
    }
}
