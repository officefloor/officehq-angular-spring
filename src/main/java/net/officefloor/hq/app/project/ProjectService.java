package net.officefloor.hq.app.project;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ClientRepository clients;
    private final Audit audit;

    public ProjectService(ProjectRepository projects, ClientRepository clients, Audit audit) {
        this.projects = projects;
        this.clients = clients;
        this.audit = audit;
    }

    /**
     * The projects, leaving out archived ones unless they are asked for, and only those carrying the
     * given tag and at the given status when those are given.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> list(boolean includeArchived, Long tagId, ProjectStatus status) {
        List<Project> found;
        if (tagId == null) {
            found = includeArchived ? projects.findAllWithClient() : projects.findActiveWithClient();
        } else {
            found = includeArchived ? projects.findAllByTagIdWithClient(tagId)
                    : projects.findActiveByTagIdWithClient(tagId);
        }
        return found.stream().filter(p -> status == null || p.getStatus() == status).map(ProjectResponse::from)
                .toList();
    }

    /**
     * A client's projects: only those under way (active status and not archived) unless all of them,
     * including finished, on hold and archived ones, are asked for.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listForClient(Long clientId, boolean includeAll) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        if (includeAll) {
            return projects.findAllByClientIdWithClient(clientId).stream().map(ProjectResponse::from).toList();
        }
        return projects.findActiveByClientIdWithClient(clientId).stream()
                .filter(p -> p.getStatus() == ProjectStatus.ACTIVE).map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id) {
        return projects.findByIdWithClient(id).map(ProjectResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
    }

    /** Adds a project; its code, trimmed and upper-cased, must not already belong to another project. */
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        Client client = clients.findById(request.clientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown client"));
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (projects.existsByCode(code)) {
            throw codeTaken();
        }
        Project project = new Project(request.name().trim(), code, client);
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        if (request.description() != null && !request.description().isBlank()) {
            project.setDescription(request.description().trim());
        }
        try {
            return ProjectResponse.from(projects.saveAndFlush(project));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent add of the same code; the unique constraint caught it.
            if (String.valueOf(e.getMessage()).toUpperCase().contains("PROJECT_CODE_UQ")) {
                throw codeTaken();
            }
            throw e;
        }
    }

    private static ResponseStatusException codeTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A job with this code already exists");
    }

    /** Invoices count as invoiced once sent; drafts have not been invoiced yet. */
    private static final List<InvoiceStatus> INVOICED =
            List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL, InvoiceStatus.PAID);

    /** The project's budget, how much has been invoiced against it, and what is left. */
    @Transactional(readOnly = true)
    public ProjectBudgetResponse budget(Long id) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        return budgetOf(project);
    }

    /** Sets (or, given none, clears) a project's budget, recording the change in the audit log. */
    @Transactional
    public ProjectBudgetResponse setBudget(Long id, BigDecimal budget) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        BigDecimal scaled = budget == null ? null : budget.setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = project.getBudget();
        boolean changed = scaled == null ? current != null : current == null || current.compareTo(scaled) != 0;
        if (changed) {
            project.setBudget(scaled);
            projects.flush();
            audit.record("PROJECT_BUDGET_SET id=" + id + " budget=" + (scaled == null ? "none" : scaled.toPlainString()));
        }
        return budgetOf(project);
    }

    private ProjectBudgetResponse budgetOf(Project project) {
        BigDecimal invoiced = projects.sumInvoiceAmountByStatusIn(project.getId(), INVOICED)
                .setScale(2, RoundingMode.HALF_UP);
        return ProjectBudgetResponse.of(project.getBudget(), invoiced);
    }

    /** Marks a project active, on hold or finished, recording the change in the audit log. */
    @Transactional
    public ProjectResponse changeStatus(Long id, ProjectStatus status) {
        Project project = projects.findByIdWithClient(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        if (project.getStatus() != status) {
            project.setStatus(status);
            projects.flush();
            audit.record("PROJECT_STATUS_CHANGED id=" + id + " status=" + status);
        }
        return ProjectResponse.from(project);
    }

    /**
     * Archives a project: it drops off the project lists but is kept, with its tasks and invoices,
     * and the archiving is recorded in the audit log.
     */
    @Transactional
    public ProjectResponse archive(Long id) {
        Project project = projects.findByIdWithClient(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        if (!project.isArchived()) {
            project.setArchived(true);
            projects.flush();
            audit.record("PROJECT_ARCHIVED id=" + id);
        }
        return ProjectResponse.from(project);
    }

    /** Brings an archived project back onto the project lists, recording it in the audit log. */
    @Transactional
    public ProjectResponse restore(Long id) {
        Project project = projects.findByIdWithClient(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        if (project.isArchived()) {
            project.setArchived(false);
            projects.flush();
            audit.record("PROJECT_RESTORED id=" + id);
        }
        return ProjectResponse.from(project);
    }
}
