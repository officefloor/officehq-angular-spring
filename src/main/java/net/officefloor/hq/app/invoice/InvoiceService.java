package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ProjectRepository projects;
    private final PaymentRepository payments;
    private final ClientRepository clients;
    private final Audit audit;

    public InvoiceService(InvoiceRepository invoices, ProjectRepository projects, PaymentRepository payments,
            ClientRepository clients, Audit audit) {
        this.invoices = invoices;
        this.projects = projects;
        this.clients = clients;
        this.payments = payments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
        List<Invoice> found = invoices.findByProjectIdOrderById(projectId);
        Map<Long, BigDecimal> paid = paidByInvoice(found);
        return found.stream()
                .map(i -> InvoiceResponse.from(i, paid.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .toList();
    }

    /**
     * A client's statement: every invoice across their projects with what is left to pay on each, and
     * the total they still owe. Drafts are listed but not owed, as they have not been sent.
     */
    @Transactional(readOnly = true)
    public ClientStatementResponse statementForClient(Long clientId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        List<Invoice> found = invoices.findByClientIdWithProject(clientId);
        Map<Long, BigDecimal> paid = paidByInvoice(found);
        List<ClientStatementResponse.Line> lines = found.stream()
                .map(i -> ClientStatementResponse.Line.from(i, paid.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .toList();
        BigDecimal outstanding = lines.stream()
                .filter(l -> l.status() != InvoiceStatus.DRAFT)
                .map(ClientStatementResponse.Line::amountDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        return new ClientStatementResponse(client.getId(), client.getName(), lines, outstanding);
    }

    private Map<Long, BigDecimal> paidByInvoice(List<Invoice> found) {
        return found.isEmpty() ? Map.of()
                : payments.sumAmountByInvoiceIds(found.stream().map(Invoice::getId).toList()).stream()
                        .collect(Collectors.toMap(PaymentRepository.InvoicePaidTotal::getInvoiceId,
                                PaymentRepository.InvoicePaidTotal::getPaid));
    }

    /**
     * One page of the invoices across all projects, with the name of the project each one is for,
     * narrowed to the given status when one is supplied. Pages are numbered from zero.
     */
    @Transactional(readOnly = true)
    public InvoicePageResponse listAll(InvoiceStatus status, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<Invoice> found = status == null ? invoices.findPageWithProject(request)
                : invoices.findPageWithProjectByStatus(status, request);
        return InvoicePageResponse.from(found);
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
        return InvoiceResponse.from(saved, BigDecimal.ZERO);
    }

    /** A single invoice with its line items. */
    @Transactional(readOnly = true)
    public InvoiceDetailResponse get(Long projectId, Long invoiceId) {
        return InvoiceDetailResponse.from(find(projectId, invoiceId));
    }

    /** Adds a line item to a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse addLineItem(Long projectId, Long invoiceId, LineItemRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.addLineItem(request.description().strip(), request.qty(), request.unitPrice());
        invoices.flush();
        return InvoiceDetailResponse.from(invoice);
    }

    /** Changes a line item on a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse updateLineItem(Long projectId, Long invoiceId, Long lineItemId,
            LineItemRequest request) {
        Invoice invoice = findDraft(projectId, invoiceId);
        InvoiceLineItem item = findLineItem(invoice, lineItemId);
        invoice.updateLineItem(item, request.description().strip(), request.qty(), request.unitPrice());
        invoices.flush();
        return InvoiceDetailResponse.from(invoice);
    }

    /** Removes a line item from a draft invoice and reworks the invoice amount to match. */
    @Transactional
    public InvoiceDetailResponse removeLineItem(Long projectId, Long invoiceId, Long lineItemId) {
        Invoice invoice = findDraft(projectId, invoiceId);
        invoice.removeLineItem(findLineItem(invoice, lineItemId));
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
        return toResponse(invoice);
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return InvoiceResponse.from(invoice, payments.sumAmountByInvoiceId(invoice.getId()));
    }

    private Invoice find(Long projectId, Long invoiceId) {
        return invoices.findById(invoiceId)
                .filter(i -> i.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
    }

    private Invoice findDraft(Long projectId, Long invoiceId) {
        Invoice invoice = find(projectId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft invoice can be changed");
        }
        return invoice;
    }

    private static InvoiceLineItem findLineItem(Invoice invoice, Long lineItemId) {
        return invoice.findLineItem(lineItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown line item"));
    }
}
