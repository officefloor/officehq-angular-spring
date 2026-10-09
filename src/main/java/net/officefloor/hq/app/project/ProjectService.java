package net.officefloor.hq.app.project;

import java.util.List;
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

    public ProjectService(ProjectRepository projects, ClientRepository clients) {
        this.projects = projects;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projects.findAllWithClient().stream().map(ProjectResponse::from).toList();
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
}
