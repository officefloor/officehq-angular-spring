package net.officefloor.hq.app.contact;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The contacts kept for a client. */
@RestController
@RequestMapping("/api/clients/{clientId}/contacts")
public class ClientContactController {

    private final ContactService service;

    public ClientContactController(ContactService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContactResponse> list(@PathVariable Long clientId) {
        return service.listForClient(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(@PathVariable Long clientId, @Valid @RequestBody ContactRequest request) {
        return service.create(clientId, request);
    }
}
