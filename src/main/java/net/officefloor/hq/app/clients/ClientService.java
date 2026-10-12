package net.officefloor.hq.app.clients;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
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

    private final Audit audit;

    public ClientService(ClientRepository clients, Audit audit) {
        this.clients = clients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Client> list() {
        return clients.findAll().stream().sorted(BY_NAME).toList();
    }

    @Transactional
    public Client add(String name, String email, Integer termsDays, BigDecimal discountPct, boolean taxExempt,
            boolean pricesIncludeTax, Currency currency, BigDecimal earlyPayPct, Integer earlyPayDays, BigDecimal retentionPct, Integer graceDays,
            BigDecimal interestRate, String region, String taxNumber, BigDecimal withholdingPct, BigDecimal minimumCharge,
            BigDecimal rateDiscountPct, LocalDate taxExemptUntil, BigDecimal creditLimit) {
        String address = email.trim();
        if (clients.existsByEmailIgnoreCase(address)) {
            throw new ClientRejectedException("Another client already has the email " + address + ".");
        }
        return clients.save(new Client(name.trim(), address, termsDays, discountPct, taxExempt, pricesIncludeTax, currency, earlyPayPct, earlyPayDays, retentionPct, graceDays, interestRate,
                region == null || region.isBlank() ? null : region.trim(), TaxNumber.normalize(taxNumber), withholdingPct, minimumCharge, rateDiscountPct, taxExemptUntil, creditLimit));
    }

    /**
     * Puts the client on hold, or lifts the hold when it is on hold, and records it in the audit; empty if
     * no such client. Lifting the hold applies nothing by itself.
     */
    @Transactional
    public Optional<Client> toggleHold(Long id) {
        Optional<Client> found = clients.findById(id);
        found.ifPresent(client -> {
            client.setOnHold(!client.isOnHold());
            clients.saveAndFlush(client);
            audit.record((client.isOnHold() ? "CLIENT_HELD id=" : "CLIENT_RELEASED id=") + client.getId());
        });
        return found;
    }
}
