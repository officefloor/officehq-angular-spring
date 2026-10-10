package net.officefloor.hq.app.contact;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContactService {

    private final ContactRepository contacts;
    private final ClientRepository clients;
    private final Audit audit;

    public ContactService(ContactRepository contacts, ClientRepository clients, Audit audit) {
        this.contacts = contacts;
        this.clients = clients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> listForClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return contacts.findByClientIdAndArchivedFalseOrderById(clientId).stream().map(ContactResponse::from).toList();
    }

    @Transactional
    public ContactResponse create(Long clientId, ContactRequest request) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        Contact saved = contacts.save(new Contact(client, request.name().trim(), request.email().trim(),
                request.role().trim()));
        // A client's first contact is its main contact until another is chosen.
        if (client.getPrimaryContact() == null) {
            client.setPrimaryContact(saved);
        }
        return ContactResponse.from(saved);
    }

    /** Makes one of a client's contacts its main contact, recording the change in the audit log. */
    @Transactional
    public ContactResponse makePrimary(Long clientId, Long contactId) {
        Contact contact = contacts.findByIdAndClientId(contactId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown contact"));
        if (contact.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An archived contact cannot be the main contact");
        }
        Client client = contact.getClient();
        Contact current = client.getPrimaryContact();
        if (current == null || !current.getId().equals(contactId)) {
            client.setPrimaryContact(contact);
            clients.flush();
            audit.record("CLIENT_PRIMARY_CONTACT_SET id=" + clientId + " contactId=" + contactId);
        }
        return ContactResponse.from(contact);
    }

    /**
     * Archives one of a client's contacts: it drops off the client's contact list but is kept, and the
     * archiving is recorded in the audit log. An archived contact is no longer the client's main contact.
     */
    @Transactional
    public ContactResponse archive(Long clientId, Long contactId) {
        Contact contact = contacts.findByIdAndClientId(contactId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown contact"));
        if (!contact.isArchived()) {
            contact.setArchived(true);
            Client client = contact.getClient();
            Contact primary = client.getPrimaryContact();
            if (primary != null && primary.getId().equals(contactId)) {
                client.setPrimaryContact(null);
            }
            contacts.flush();
            audit.record("CONTACT_ARCHIVED id=" + contactId + " clientId=" + clientId);
        }
        return ContactResponse.from(contact);
    }
}
