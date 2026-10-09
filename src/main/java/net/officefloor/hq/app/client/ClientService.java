package net.officefloor.hq.app.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.contact.ContactRepository;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientService {

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final ContactRepository contacts;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final Audit audit;

    public ClientService(ClientRepository clients, ProjectRepository projects, ContactRepository contacts,
            InvoiceRepository invoices, PaymentRepository payments, Audit audit) {
        this.clients = clients;
        this.projects = projects;
        this.contacts = contacts;
        this.invoices = invoices;
        this.payments = payments;
        this.audit = audit;
    }

    /** The clients, leaving out archived ones unless they are asked for. */
    @Transactional(readOnly = true)
    public List<ClientResponse> list(boolean includeArchived) {
        List<Client> found = includeArchived ? clients.findAll(Sort.by("id")) : clients.findByArchivedFalseOrderById();
        return respond(found);
    }

    @Transactional(readOnly = true)
    public ClientResponse get(Long id) {
        return respond(find(id));
    }

    /** At-a-glance counts of what one client has. */
    @Transactional(readOnly = true)
    public ClientSummaryResponse summary(Long id) {
        if (!clients.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return new ClientSummaryResponse(projects.countByClientId(id), contacts.countByClientId(id));
    }

    /** Adds a client; its email, once trimmed, must not already belong to another client. */
    @Transactional
    public ClientResponse create(ClientRequest request) {
        String email = request.email().trim();
        if (clients.existsByEmailIgnoreCase(email)) {
            throw emailTaken();
        }
        try {
            Client client = new Client(request.name().trim(), email, request.trimmedPhone(), request.trimmedTaxNumber(), request.trimmedBillingAddress());
            client.setTaxInclusive(Boolean.TRUE.equals(request.taxInclusive()));
            client.setTaxExempt(Boolean.TRUE.equals(request.taxExempt()));
            client.setKeyAccount(Boolean.TRUE.equals(request.keyAccount()));
            if (request.defaultDiscountPct() != null) {
                client.setDefaultDiscountPct(request.defaultDiscountPct());
            }
            return respond(clients.saveAndFlush(client));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent add of the same email; the unique constraint caught it.
            if (String.valueOf(e.getMessage()).toUpperCase().contains("CLIENT_EMAIL_UQ")) {
                throw emailTaken();
            }
            throw e;
        }
    }

    /**
     * Corrects a client's name, email, phone number, tax number and billing address; the email, once trimmed, must not belong to another client.
     * When whether their prices include tax, or whether they are tax exempt, changes, their draft invoices are reworked to match; sent ones keep the
     * figures they were issued with. The change is recorded in the audit log.
     */
    @Transactional
    public ClientResponse update(Long id, ClientRequest request) {
        Client client = find(id);
        String email = request.email().trim();
        if (clients.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw emailTaken();
        }
        client.rename(request.name().trim(), email, request.trimmedPhone(), request.trimmedTaxNumber(), request.trimmedBillingAddress());
        if (request.taxInclusive() != null && request.taxInclusive() != client.isTaxInclusive()) {
            client.setTaxInclusive(request.taxInclusive());
            invoices.findByClientIdAndStatus(id, InvoiceStatus.DRAFT)
                    .forEach(i -> i.applyTaxInclusive(request.taxInclusive()));
        }
        if (request.taxExempt() != null && request.taxExempt() != client.isTaxExempt()) {
            client.setTaxExempt(request.taxExempt());
            invoices.findByClientIdAndStatus(id, InvoiceStatus.DRAFT)
                    .forEach(i -> i.applyTaxExempt(request.taxExempt()));
        }
        if (request.keyAccount() != null) {
            client.setKeyAccount(request.keyAccount());
        }
        // Only invoices raised from now on start with the standard discount; existing ones keep theirs.
        if (request.defaultDiscountPct() != null) {
            client.setDefaultDiscountPct(request.defaultDiscountPct());
        }
        try {
            clients.flush();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent change to the same email; the unique constraint caught it.
            if (String.valueOf(e.getMessage()).toUpperCase().contains("CLIENT_EMAIL_UQ")) {
                throw emailTaken();
            }
            throw e;
        }
        audit.record("CLIENT_UPDATED id=" + id);
        return respond(client);
    }

    /** Changes the currency a client is billed in, recording the change in the audit log. */
    @Transactional
    public ClientResponse changeCurrency(Long id, Currency currency) {
        Client client = find(id);
        if (client.getCurrency() != currency) {
            client.setCurrency(currency);
            clients.flush();
            audit.record("CLIENT_CURRENCY_CHANGED id=" + id + " currency=" + currency);
        }
        return respond(client);
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A client with this email already exists");
    }

    /**
     * Archives a client: it drops off the client list and search but is kept, with its projects and
     * contacts, and the archiving is recorded in the audit log.
     */
    @Transactional
    public ClientResponse archive(Long id) {
        Client client = find(id);
        if (!client.isArchived()) {
            client.setArchived(true);
            clients.flush();
            audit.record("CLIENT_ARCHIVED id=" + id);
        }
        return respond(client);
    }

    /** Brings an archived client back onto the client list, recording it in the audit log. */
    @Transactional
    public ClientResponse restore(Long id) {
        Client client = find(id);
        if (client.isArchived()) {
            client.setArchived(false);
            clients.flush();
            audit.record("CLIENT_RESTORED id=" + id);
        }
        return respond(client);
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** The given clients, each with what they still owe. */
    @Transactional(readOnly = true)
    public List<ClientResponse> respond(List<Client> found) {
        Map<Long, BigDecimal> owed = outstandingByClient();
        return found.stream().map(c -> ClientResponse.from(c, owed.getOrDefault(c.getId(), ZERO))).toList();
    }

    /**
     * The clients who owe the most, largest debt first (ties by id), at most {@code limit} of them.
     * Clients owing nothing are left out.
     */
    @Transactional(readOnly = true)
    public List<ClientResponse> topByOutstanding(int limit) {
        Map<Long, BigDecimal> owed = outstandingByClient();
        owed.values().removeIf(amount -> amount.signum() <= 0);
        List<Long> top = owed.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        Map<Long, Client> found = new HashMap<>();
        clients.findAllById(top).forEach(c -> found.put(c.getId(), c));
        return top.stream().map(id -> ClientResponse.from(found.get(id), owed.get(id))).toList();
    }

    /**
     * What is still owed in each currency, each client's debt counted in their own currency; amounts
     * in different currencies are never added together. Currencies nothing is owed in are left out.
     */
    @Transactional(readOnly = true)
    public Map<Currency, BigDecimal> outstandingByCurrency() {
        Map<Long, BigDecimal> owed = outstandingByClient();
        Map<Currency, BigDecimal> totals = new EnumMap<>(Currency.class);
        clients.findAllById(owed.keySet())
                .forEach(c -> totals.merge(c.getCurrency(), owed.get(c.getId()), BigDecimal::add));
        totals.values().removeIf(amount -> amount.signum() == 0);
        return totals;
    }

    private ClientResponse respond(Client client) {
        return ClientResponse.from(client, outstandingByClient().getOrDefault(client.getId(), ZERO));
    }

    /**
     * What each client still owes: what is left to pay on their sent invoices that are not yet fully
     * paid. Clients owing nothing are left out.
     */
    private Map<Long, BigDecimal> outstandingByClient() {
        List<InvoiceStatus> owing = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIAL);
        Map<Long, BigDecimal> owed = new HashMap<>();
        invoices.sumAmountByStatusInPerClient(owing).forEach(t -> owed.merge(t.getClientId(), t.getTotal(),
                BigDecimal::add));
        payments.sumAmountByInvoiceStatusInPerClient(owing).forEach(t -> owed.merge(t.getClientId(),
                t.getTotal().negate(), BigDecimal::add));
        owed.replaceAll((id, amount) -> amount.setScale(2, RoundingMode.HALF_UP));
        return owed;
    }

    private Client find(Long id) {
        return clients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client"));
    }
}
