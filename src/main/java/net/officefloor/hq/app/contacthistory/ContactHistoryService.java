package net.officefloor.hq.app.contacthistory;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.ClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContactHistoryService {

    private final ContactHistoryRepository history;
    private final ClientRepository clients;
    private final Audit audit;

    public ContactHistoryService(ContactHistoryRepository history, ClientRepository clients, Audit audit) {
        this.history = history;
        this.clients = clients;
        this.audit = audit;
    }

    /** When the client was contacted, newest first. */
    @Transactional(readOnly = true)
    public List<ContactHistoryResponse> listForClient(Long clientId) {
        requireClient(clientId);
        return history.findByClientIdOrderByDateDescIdDesc(clientId).stream()
                .map(ContactHistoryResponse::from).toList();
    }

    /** Records that the client was contacted, noting it in the audit log. */
    @Transactional
    public ContactHistoryResponse record(Long clientId, ContactHistoryRequest request) {
        requireClient(clientId);
        ContactHistoryEntry saved = history.saveAndFlush(
                new ContactHistoryEntry(clientId, request.date(), request.note().trim()));
        audit.record("CLIENT_CONTACT_RECORDED id=" + clientId + " entryId=" + saved.getId());
        return ContactHistoryResponse.from(saved);
    }

    private void requireClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
    }
}
