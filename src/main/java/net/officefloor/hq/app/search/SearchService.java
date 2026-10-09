package net.officefloor.hq.app.search;

import java.util.List;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.client.ClientResponse;
import net.officefloor.hq.app.project.ProjectRepository;
import net.officefloor.hq.app.project.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {

    private final ClientRepository clients;
    private final ProjectRepository projects;

    public SearchService(ClientRepository clients, ProjectRepository projects) {
        this.clients = clients;
        this.projects = projects;
    }

    /**
     * The clients and projects, not archived, whose name contains the query (ignoring case); a blank
     * query finds nothing.
     */
    @Transactional(readOnly = true)
    public SearchResponse search(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return new SearchResponse(List.of(), List.of());
        }
        String pattern = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return new SearchResponse(
                clients.searchActiveByName(pattern).stream().map(ClientResponse::from).toList(),
                projects.searchActiveByNameWithClient(pattern).stream().map(ProjectResponse::from).toList());
    }
}
