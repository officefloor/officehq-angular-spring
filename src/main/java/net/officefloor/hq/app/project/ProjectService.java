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

    /**
     * The projects, leaving out archived ones unless they are asked for, and only those carrying the
     * given tag when one is given.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> list(boolean includeArchived, Long tagId) {
        List<Project> found;
        if (tagId == null) {
            found = includeArchived ? projects.findAllWithClient() : projects.findActiveWithClient();
        } else {
            found = includeArchived ? projects.findAllByTagIdWithClient(tagId)
                    : projects.findActiveByTagIdWithClient(tagId);
        }
        return found.stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return projects.findActiveByClientIdWithClient(clientId).stream().map(ProjectResponse::from).toList();
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
     * Archives a project: it drops off the project lists but is kept, with its tasks and invoices,
     * and the archiving is recorded in the audit log.
     */
    @Transactional
    public ProjectResponse archive(Long id) {
        Project project = projects.findByIdWithClient(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        if (project.isArchived()) {
            project.setArchived(false);
            projects.flush();
            audit.record("PROJECT_RESTORED id=" + id);
        }
        return ProjectResponse.from(project);
    }
}
