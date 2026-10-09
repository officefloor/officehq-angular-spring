package net.officefloor.hq.app.credit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.client.ClientRepository;
import net.officefloor.hq.app.creditnote.CreditNoteRepository;
import net.officefloor.hq.app.deposit.DepositApplicationRepository;
import net.officefloor.hq.app.deposit.DepositRepository;
import net.officefloor.hq.app.invoice.Invoice;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import net.officefloor.hq.app.payment.PaymentRepository;
import net.officefloor.hq.app.refund.RefundRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientCreditService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final ClientRepository clients;
    private final DepositRepository deposits;
    private final DepositApplicationRepository applications;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final RefundRepository refunds;

    public ClientCreditService(ClientRepository clients, DepositRepository deposits,
            DepositApplicationRepository applications, InvoiceRepository invoices, PaymentRepository payments,
            CreditNoteRepository creditNotes, RefundRepository refunds) {
        this.clients = clients;
        this.deposits = deposits;
        this.applications = applications;
        this.invoices = invoices;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
    }

    /**
     * Works out how much credit a client has to spend. A deposit is unused until it is put toward invoices. A credit
     * note is used only as far as it was needed to settle its invoice once payments are counted; anything beyond
     * that (say, a credit on an invoice already paid in full, or on one since cancelled) is still the client's to spend.
     * Whatever has been refunded to the client is no longer theirs to spend.
     */
    @Transactional(readOnly = true)
    public ClientCreditResponse available(Long clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client");
        }
        BigDecimal held = deposits.sumAmountByClientId(clientId)
                .subtract(applications.sumAmountByClientId(clientId))
                .subtract(refunds.sumFromDepositsByClientId(clientId)).setScale(2).max(ZERO);
        BigDecimal unusedCredit = unusedCreditNotes(clientId)
                .subtract(refunds.sumFromCreditNotesByClientId(clientId)).setScale(2).max(ZERO);
        return new ClientCreditResponse(held, unusedCredit, held.add(unusedCredit));
    }

    private BigDecimal unusedCreditNotes(Long clientId) {
        List<Invoice> clientInvoices = invoices.findByClientIdWithProject(clientId);
        List<Long> ids = clientInvoices.stream().map(Invoice::getId).toList();
        if (ids.isEmpty()) {
            return ZERO;
        }
        Map<Long, BigDecimal> credited = creditNotes.sumAmountByInvoiceIds(ids).stream().collect(Collectors.toMap(
                CreditNoteRepository.InvoiceCreditedTotal::getInvoiceId,
                CreditNoteRepository.InvoiceCreditedTotal::getCredited));
        if (credited.isEmpty()) {
            return ZERO;
        }
        Map<Long, BigDecimal> paid = payments.sumAmountByInvoiceIds(ids).stream().collect(Collectors.toMap(
                PaymentRepository.InvoicePaidTotal::getInvoiceId, PaymentRepository.InvoicePaidTotal::getPaid));
        BigDecimal unused = ZERO;
        for (Invoice invoice : clientInvoices) {
            BigDecimal credit = credited.get(invoice.getId());
            if (credit == null) {
                continue;
            }
            // A cancelled invoice is no longer owed, so none of its credit went toward settling it.
            BigDecimal owed = invoice.getStatus() == InvoiceStatus.VOID ? ZERO
                    : invoice.getAmount().subtract(paid.getOrDefault(invoice.getId(), ZERO)).max(ZERO);
            unused = unused.add(credit.subtract(credit.min(owed)));
        }
        return unused.setScale(2);
    }
}
