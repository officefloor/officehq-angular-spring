package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.payment.ClientPaymentRepository;
import net.officefloor.hq.app.refund.RefundRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DepositService {

    private final DepositRepository deposits;
    private final DepositApplicationRepository applications;
    private final RefundRepository refunds;
    private final ClientPaymentRepository clientPayments;
    private final ClientRepository clients;
    private final Audit audit;

    public DepositService(DepositRepository deposits, DepositApplicationRepository applications,
            RefundRepository refunds, ClientPaymentRepository clientPayments, ClientRepository clients, Audit audit) {
        this.deposits = deposits;
        this.applications = applications;
        this.refunds = refunds;
        this.clientPayments = clientPayments;
        this.clients = clients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ClientDepositsResponse list(Long clientId) {
        requireClient(clientId);
        List<DepositResponse> held = deposits.findByClientIdOrderByDateAscIdAsc(clientId).stream()
                .map(DepositResponse::from).toList();
        BigDecimal paid = held.stream().map(DepositResponse::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        // Overpayments kept as credit are held alongside the deposits; deposits used up by payments count as applied.
        BigDecimal overpaid = clientPayments.sumToCreditByClientId(clientId).setScale(2);
        BigDecimal applied = applications.sumAmountByClientId(clientId)
                .add(clientPayments.sumFromDepositsByClientId(clientId)).setScale(2);
        BigDecimal refunded = refunds.sumFromDepositsByClientId(clientId).setScale(2);
        return new ClientDepositsResponse(paid.add(overpaid).subtract(applied).subtract(refunded), applied, refunded,
                held);
    }

    /** Records money a client paid up front, before any invoice; it is held against the client. */
    @Transactional
    public DepositResponse record(Long clientId, DepositRequest request) {
        requireClient(clientId);
        Deposit saved = deposits.saveAndFlush(new Deposit(clientId, request.amount(), request.date()));
        audit.record("DEPOSIT_RECORDED id=" + saved.getId() + " client=" + clientId
                + " amount=" + saved.getAmount().toPlainString());
        return DepositResponse.from(saved);
    }

    private void requireClient(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
    }
}
