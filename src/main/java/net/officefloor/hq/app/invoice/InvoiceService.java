package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * Every invoice across all projects, with the name of the project each one is for, narrowed to
     * the given status when one is supplied.
     */
    @Transactional(readOnly = true)
    public List<InvoiceSummaryResponse> listAll(InvoiceStatus status) {
        List<Invoice> found =
                status == null ? invoices.findAllWithProject() : invoices.findAllWithProjectByStatus(status);
        return found.stream().map(InvoiceSummaryResponse::from).toList();
    }

    @Transactional
    public InvoiceResponse create(Long projectId, InvoiceRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        LocalDate issued = request.issuedDate() != null ? request.issuedDate() : LocalDate.now();
        LocalDate due = request.dueDate() != null ? request.dueDate() : issued.plusDays(InvoiceRequest.DEFAULT_TERM_DAYS);
        if (due.isBefore(issued)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Due date must not be before the issue date");
        }
        Invoice invoice = new Invoice(project, issued, due);
        if (request.amount() != null) {
            invoice.addLineItem(InvoiceRequest.SINGLE_AMOUNT_DESCRIPTION, BigDecimal.ONE, request.amount());
        }
        Invoice saved = invoices.save(invoice);
        return InvoiceResponse.from(saved);
    }

    /** A single invoice with its line items. */
    @Transactional(readOnly = true)
    public InvoiceDetailResponse get(Long projectId, Long invoiceId) {
        return InvoiceDetailResponse.from(find(projectId, invoiceId));
    }

    /** Adds a line item to a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse addLineItem(Long projectId, Long invoiceId, LineItemRequest request) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be changed");
        }
        invoice.addLineItem(request.description().strip(), request.qty(), request.unitPrice());
        invoices.flush();
        return InvoiceDetailResponse.from(invoice);
    }

    /** Sends a draft invoice and records the sending in the audit log. */
    @Transactional
    public InvoiceResponse send(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be sent");
        }
        if (invoice.getAmount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Add a line item before sending the invoice");
        }
        invoice.markSent();
        invoices.flush();
        audit.record("INVOICE_SENT id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return InvoiceResponse.from(invoice);
    }

    /** Marks a sent invoice paid and records the payment in the audit log. */
    @Transactional
    public InvoiceResponse pay(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.SENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a sent invoice can be paid");
        }
        invoice.markPaid();
        invoices.flush();
        audit.record("INVOICE_PAID id=" + invoice.getId() + " amount=" + invoice.getAmount().toPlainString());
        return InvoiceResponse.from(invoice);
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }
}
