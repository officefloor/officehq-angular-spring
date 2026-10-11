package net.officefloor.hq.app.clients;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists and adds clients. Clients are listed A to Z by name, ignoring capitals. */
@Service
public class ClientService {

    static final Comparator<Client> BY_NAME = Comparator
            .comparing(Client::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Client::getName)
            .thenComparing(Client::getId);

    private final ClientRepository clients;

    public ClientService(ClientRepository clients) {
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<Client> list() {
        return clients.findAll().stream().sorted(BY_NAME).toList();
    }

    @Transactional
    public Client add(String name, String email) {
        return clients.save(new Client(name.trim(), email.trim()));
    }
}
