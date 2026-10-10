package net.officefloor.hq.app.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.invoice.InvoiceRequest;
import net.officefloor.hq.app.invoice.InvoiceResponse;
import net.officefloor.hq.app.invoice.InvoiceService;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecurringInvoiceService {

    private final RecurringInvoiceRepository recurring;
    private final ProjectRepository projects;
    private final InvoiceService invoices;
    private final Audit audit;
    private final Clock clock;

    public RecurringInvoiceService(RecurringInvoiceRepository recurring, ProjectRepository projects,
            InvoiceService invoices, Audit audit, Clock clock) {
        this.recurring = recurring;
        this.projects = projects;
        this.invoices = invoices;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecurringInvoiceResponse> list(Long projectId) {
        requireProject(projectId);
        LocalDate today = LocalDate.now(clock);
        return recurring.findByProjectIdOrderByNextDateAscIdAsc(projectId).stream()
                .map(r -> RecurringInvoiceResponse.from(r, today)).toList();
    }

    /** The active recurring invoices across all projects next falling today or later, soonest first. */
    @Transactional(readOnly = true)
    public List<UpcomingRecurringInvoiceResponse> upcoming() {
        List<RecurringInvoice> found = recurring.findByStatusAndNextDateGreaterThanEqualOrderByNextDateAscIdAsc(
                RecurringStatus.ACTIVE, LocalDate.now(clock));
        List<Long> projectIds = found.stream().map(RecurringInvoice::getProjectId).distinct().toList();
        Map<Long, Project> byId = projects.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        return found.stream().map(r -> UpcomingRecurringInvoiceResponse.from(r, byId.get(r.getProjectId()))).toList();
    }

    /** Sets up an invoice on the project that repeats for a fixed amount, the first falling on the given day. */
    @Transactional
    public RecurringInvoiceResponse create(Long projectId, RecurringInvoiceRequest request) {
        requireProject(projectId);
        RecurringInvoice saved = recurring.saveAndFlush(
                new RecurringInvoice(projectId, request.amount(), request.frequency(), request.nextDate()));
        audit.record("RECURRING_INVOICE_CREATED id=" + saved.getId() + " project=" + projectId
                + " amount=" + saved.getAmount().toPlainString() + " frequency=" + saved.getFrequency());
        return RecurringInvoiceResponse.from(saved, LocalDate.now(clock));
    }

    /**
     * Raises the invoice a recurring schedule has fallen due for, dated the day it fell due, and moves the
     * schedule on to the next one. The invoice is raised as a DRAFT so it can be reviewed before it is sent.
     */
    @Transactional
    public InvoiceResponse generate(Long projectId, Long recurringId) {
        RecurringInvoice schedule = requireSchedule(projectId, recurringId);
        if (schedule.isPaused()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The recurring invoice is paused");
        }
        LocalDate due = schedule.getNextDate();
        if (due.isAfter(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The recurring invoice is not due yet");
        }
        InvoiceResponse invoice = invoices.create(projectId, new InvoiceRequest(schedule.getAmount(), due, null, null));
        schedule.advance();
        recurring.saveAndFlush(schedule);
        audit.record("RECURRING_INVOICE_GENERATED id=" + recurringId + " project=" + projectId
                + " invoice=" + invoice.id() + " status=" + invoice.status() + " nextDate=" + schedule.getNextDate());
        return invoice;
    }

    /** Pauses the schedule so it stops generating invoices until it is resumed. */
    @Transactional
    public RecurringInvoiceResponse pause(Long projectId, Long recurringId) {
        RecurringInvoice schedule = requireSchedule(projectId, recurringId);
        if (!schedule.isPaused()) {
            schedule.pause();
            recurring.saveAndFlush(schedule);
            audit.record("RECURRING_INVOICE_PAUSED id=" + recurringId + " project=" + projectId);
        }
        return RecurringInvoiceResponse.from(schedule, LocalDate.now(clock));
    }

    /** Resumes a paused schedule so it generates invoices again. */
    @Transactional
    public RecurringInvoiceResponse resume(Long projectId, Long recurringId) {
        RecurringInvoice schedule = requireSchedule(projectId, recurringId);
        if (schedule.isPaused()) {
            schedule.resume();
            recurring.saveAndFlush(schedule);
            audit.record("RECURRING_INVOICE_RESUMED id=" + recurringId + " project=" + projectId);
        }
        return RecurringInvoiceResponse.from(schedule, LocalDate.now(clock));
    }

    private RecurringInvoice requireSchedule(Long projectId, Long recurringId) {
        return recurring.findById(recurringId)
                .filter(r -> r.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown recurring invoice"));
    }

    private void requireProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
    }
}
