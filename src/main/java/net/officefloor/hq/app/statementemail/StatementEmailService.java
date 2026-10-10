package net.officefloor.hq.app.statementemail;

import java.time.Clock;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.client.ClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementEmailService {

    private final StatementEmailRepository sent;
    private final ClientRepository clients;
    private final Audit audit;
    private final Clock clock;

    public StatementEmailService(StatementEmailRepository sent, ClientRepository clients, Audit audit, Clock clock) {
        this.sent = sent;
        this.clients = clients;
        this.audit = audit;
        this.clock = clock;
    }

    /** When the client's statement was emailed, newest first. */
    @Transactional(readOnly = true)
    public List<StatementEmailResponse> listForClient(Long clientId) {
        requireClient(clientId);
        return sent.findByClientIdOrderBySentAtDescIdDesc(clientId).stream()
                .map(StatementEmailResponse::from).toList();
    }

    /** Emails the client their statement, keeping a note of it and recording it in the audit log. */
    @Transactional
    public StatementEmailResponse email(Long clientId) {
        Client client = requireClient(clientId);
        String email = client.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The client has no email address");
        }
        StatementEmail saved = sent.saveAndFlush(new StatementEmail(clientId, email, clock.instant()));
        audit.record("STATEMENT_EMAILED client=" + clientId);
        return StatementEmailResponse.from(saved);
    }

    private Client requireClient(Long clientId) {
        return clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
    }
}
