package net.officefloor.hq.app.client;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.contact.ContactRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Adds a client; its email, once trimmed, must not already belong to another client. */
    @Transactional
    public ClientResponse create(ClientRequest request) {
        String email = request.email().trim();
        if (clients.existsByEmailIgnoreCase(email)) {
            throw emailTaken();
        }
        try {
            return ClientResponse.from(clients.saveAndFlush(new Client(request.name().trim(), email)));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent add of the same email; the unique constraint caught it.
            if (String.valueOf(e.getMessage()).toUpperCase().contains("CLIENT_EMAIL_UQ")) {
                throw emailTaken();
            }
            throw e;
        }
    }

    /**
     * Corrects a client's name and email; the email, once trimmed, must not belong to another client.
     * The change is recorded in the audit log.
     */
    @Transactional
    public ClientResponse update(Long id, ClientRequest request) {
        Client client = find(id);
        String email = request.email().trim();
        if (clients.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw emailTaken();
        }
        client.rename(request.name().trim(), email);
        try {
            clients.flush();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent change to the same email; the unique constraint caught it.
            if (String.valueOf(e.getMessage()).toUpperCase().contains("CLIENT_EMAIL_UQ")) {
                throw emailTaken();
            }
            throw e;
        }
        audit.record("CLIENT_UPDATED id=" + id);
        return ClientResponse.from(client);
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A client with this email already exists");
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
