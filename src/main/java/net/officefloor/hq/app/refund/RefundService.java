package net.officefloor.hq.app.refund;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.credit.ClientCreditResponse;
import net.officefloor.hq.app.credit.ClientCreditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundService {

    private final RefundRepository refunds;
    private final ClientCreditService credit;
    private final ClientRepository clients;
    private final Clock clock;
    private final Audit audit;

    public RefundService(RefundRepository refunds, ClientCreditService credit, ClientRepository clients, Clock clock,
            Audit audit) {
        this.refunds = refunds;
        this.credit = credit;
        this.clients = clients;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> list(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        return refunds.findByClientIdOrderByDateDescIdDesc(clientId).stream().map(RefundResponse::from).toList();
    }

    /**
     * Pays part of a client's unused credit back to them, today. It may not be more than the credit
     * they have; it comes out of their held deposits first, then their unused credit notes.
     */
    @Transactional
    public RefundResponse refund(Long clientId, RefundRequest request) {
        ClientCreditResponse available = credit.available(clientId);
        BigDecimal amount = request.amount().setScale(2);
        if (amount.compareTo(available.total()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The refund is more than the client's available credit");
        }
        BigDecimal fromDeposits = amount.min(available.deposits());
        BigDecimal fromCreditNotes = amount.subtract(fromDeposits);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        Refund saved = refunds.saveAndFlush(
                new Refund(clientId, fromDeposits, fromCreditNotes, LocalDate.now(clock), note));
        audit.record("REFUND_ISSUED client=" + clientId + " amount=" + saved.getAmount().toPlainString());
        return RefundResponse.from(saved);
    }
}
