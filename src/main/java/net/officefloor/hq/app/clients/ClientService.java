package net.officefloor.hq.app.clients;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and adds clients. Clients are listed A to Z by name, ignoring capitals. No two clients share
 * an email address, ignoring capitals.
 */
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
    public Client add(String name, String email, Integer termsDays, BigDecimal discountPct, boolean taxExempt,
            Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays) {
        String address = email.trim();
        if (clients.existsByEmailIgnoreCase(address)) {
            throw new ClientRejectedException("Another client already has the email " + address + ".");
        }
        return clients.save(new Client(name.trim(), address, termsDays, discountPct, taxExempt, currency, earlyPayPct, earlyPayDays));
    }
}
