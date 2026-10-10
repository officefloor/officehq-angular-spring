package net.officefloor.hq.app.statementemail;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Emailing a client their statement, and the note of each time it was sent. */
@RestController
@RequestMapping("/api/clients/{clientId}/statement/emails")
public class StatementEmailController {

    private final StatementEmailService service;

    public StatementEmailController(StatementEmailService service) {
        this.service = service;
    }

    @GetMapping
    public List<StatementEmailResponse> list(@PathVariable Long clientId) {
        return service.listForClient(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StatementEmailResponse email(@PathVariable Long clientId) {
        return service.email(clientId);
    }
}
