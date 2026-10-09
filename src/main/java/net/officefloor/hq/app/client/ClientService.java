package net.officefloor.hq.app.client;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientService {

    private final ClientRepository clients;

    public ClientService(ClientRepository clients) {
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> list() {
        return clients.findAll(Sort.by("id")).stream().map(ClientResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClientResponse get(Long id) {
        return clients.findById(id).map(ClientResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
    }

    @Transactional
    public ClientResponse create(ClientRequest request) {
        Client saved = clients.save(new Client(request.name().trim(), request.email().trim()));
        return ClientResponse.from(saved);
    }
}
