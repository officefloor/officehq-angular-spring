package net.officefloor.hq.app.contacthistory;

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

/** The history of when a client was contacted. */
@RestController
@RequestMapping("/api/clients/{clientId}/contact-history")
public class ClientContactHistoryController {

    private final ContactHistoryService service;

    public ClientContactHistoryController(ContactHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContactHistoryResponse> list(@PathVariable Long clientId) {
        return service.listForClient(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactHistoryResponse record(@PathVariable Long clientId,
            @Valid @RequestBody ContactHistoryRequest request) {
        return service.record(clientId, request);
    }
}
