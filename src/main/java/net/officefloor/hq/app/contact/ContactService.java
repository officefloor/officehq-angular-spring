package net.officefloor.hq.app.contact;

import java.util.List;
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

    public ContactService(ContactRepository contacts, ClientRepository clients) {
        this.contacts = contacts;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> listForClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return contacts.findByClientIdOrderById(clientId).stream().map(ContactResponse::from).toList();
    }

    @Transactional
    public ContactResponse create(Long clientId, ContactRequest request) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
        Contact saved = contacts.save(new Contact(client, request.name().trim(), request.email().trim(),
                request.role().trim()));
        return ContactResponse.from(saved);
    }
}
