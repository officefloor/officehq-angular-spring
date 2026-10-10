package net.officefloor.hq.app.recurring;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecurringInvoiceService {

    private final RecurringInvoiceRepository recurring;
    private final ProjectRepository projects;
    private final Audit audit;

    public RecurringInvoiceService(RecurringInvoiceRepository recurring, ProjectRepository projects, Audit audit) {
        this.recurring = recurring;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RecurringInvoiceResponse> list(Long projectId) {
        requireProject(projectId);
        return recurring.findByProjectIdOrderByNextDateAscIdAsc(projectId).stream()
                .map(RecurringInvoiceResponse::from).toList();
    }

    /** Sets up an invoice on the project that repeats for a fixed amount, the first falling on the given day. */
    @Transactional
    public RecurringInvoiceResponse create(Long projectId, RecurringInvoiceRequest request) {
        requireProject(projectId);
        RecurringInvoice saved = recurring.saveAndFlush(
                new RecurringInvoice(projectId, request.amount(), request.frequency(), request.nextDate()));
        audit.record("RECURRING_INVOICE_CREATED id=" + saved.getId() + " project=" + projectId
                + " amount=" + saved.getAmount().toPlainString() + " frequency=" + saved.getFrequency());
        return RecurringInvoiceResponse.from(saved);
    }

    private void requireProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
    }
}
