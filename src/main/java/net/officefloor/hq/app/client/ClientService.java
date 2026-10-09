package net.officefloor.hq.app.client;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public ClientResponse create(ClientRequest request) {
        Client saved = clients.save(new Client(request.name().trim(), request.email().trim()));
        return ClientResponse.from(saved);
    }
}
