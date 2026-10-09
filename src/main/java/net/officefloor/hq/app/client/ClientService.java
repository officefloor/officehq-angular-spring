package net.officefloor.hq.app.client;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.contact.ContactRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientService {

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final ContactRepository contacts;
    private final Audit audit;

    public ClientService(ClientRepository clients, ProjectRepository projects, ContactRepository contacts,
            Audit audit) {
        this.clients = clients;
        this.projects = projects;
        this.contacts = contacts;
        this.audit = audit;
    }

    /** The clients, leaving out archived ones unless they are asked for. */
    @Transactional(readOnly = true)
    public List<ClientResponse> list(boolean includeArchived) {
        List<Client> found = includeArchived ? clients.findAll(Sort.by("id")) : clients.findByArchivedFalseOrderById();
        return found.stream().map(ClientResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClientResponse get(Long id) {
        return clients.findById(id).map(ClientResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
    }

    /** At-a-glance counts of what one client has. */
    @Transactional(readOnly = true)
    public ClientSummaryResponse summary(Long id) {
        if (!clients.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return new ClientSummaryResponse(projects.countByClientId(id), contacts.countByClientId(id));
    }

    @Transactional
    public ClientResponse create(ClientRequest request) {
        Client saved = clients.save(new Client(request.name().trim(), request.email().trim()));
        return ClientResponse.from(saved);
    }

    /**
     * Archives a client: it drops off the client list and search but is kept, with its projects and
     * contacts, and the archiving is recorded in the audit log.
     */
    @Transactional
    public ClientResponse archive(Long id) {
        Client client = find(id);
        if (!client.isArchived()) {
            client.setArchived(true);
            clients.flush();
            audit.record("CLIENT_ARCHIVED id=" + id);
        }
        return ClientResponse.from(client);
    }

    /** Brings an archived client back onto the client list, recording it in the audit log. */
    @Transactional
    public ClientResponse restore(Long id) {
        Client client = find(id);
        if (client.isArchived()) {
            client.setArchived(false);
            clients.flush();
            audit.record("CLIENT_RESTORED id=" + id);
        }
        return ClientResponse.from(client);
    }

    private Client find(Long id) {
        return clients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
    }
}
