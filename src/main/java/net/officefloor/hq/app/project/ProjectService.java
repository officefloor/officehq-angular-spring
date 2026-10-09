package net.officefloor.hq.app.project;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
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

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projects.findAllWithClient().stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return projects.findByClientIdWithClient(clientId).stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id) {
        return projects.findByIdWithClient(id).map(ProjectResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        Client client = clients.findById(request.clientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown client"));
        Project saved = projects.save(new Project(request.name().trim(), client));
        return ProjectResponse.from(saved);
    }

    /**
     * Deletes a project (and its tasks) and records the deletion in the audit log. A project that
     * has been invoiced is kept, so its invoices are never lost.
     */
    @Transactional
    public void delete(Long id) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        if (projects.hasInvoices(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project has invoices");
        }
        projects.delete(project);
        projects.flush();
        audit.record("PROJECT_DELETED id=" + id);
    }
}
