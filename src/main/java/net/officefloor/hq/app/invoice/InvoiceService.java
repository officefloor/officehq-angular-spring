package net.officefloor.hq.app.invoice;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ProjectRepository projects;
    private final Audit audit;

    public InvoiceService(InvoiceRepository invoices, ProjectRepository projects, Audit audit) {
        this.invoices = invoices;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
        return invoices.findByProjectIdOrderById(projectId).stream().map(InvoiceResponse::from).toList();
    }

    @Transactional
    public InvoiceResponse create(Long projectId, InvoiceRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        Invoice saved = invoices.save(new Invoice(project, request.amount()));
        return InvoiceResponse.from(saved);
    }

    /** Marks the invoice paid and records the payment in the audit log. */
    @Transactional
    public InvoiceResponse pay(Long projectId, Long invoiceId) {
        Invoice invoice = invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice is already paid");
        }
        invoice.markPaid();
        invoices.flush();
        audit.record("INVOICE_PAID id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return InvoiceResponse.from(invoice);
    }
}
