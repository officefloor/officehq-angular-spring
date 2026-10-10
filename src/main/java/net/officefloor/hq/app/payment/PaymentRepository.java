package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** An invoice's payments, oldest first. */
    List<Payment> findByInvoiceIdOrderByDateAscIdAsc(Long invoiceId);

    /** The payments split from a lump payment, in the order they were recorded. */
    List<Payment> findByClientPaymentIdOrderByIdAsc(Long clientPaymentId);

    /** Every payment against any of the given invoices. */
    List<Payment> findByInvoiceIdIn(Collection<Long> invoiceIds);

    /** Every payment made on or between the given dates. */
    List<Payment> findByDateBetween(LocalDate from, LocalDate to);

    /** Total paid against an invoice (zero when nothing has been paid). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);

    /** Total paid against an invoice on days before the given date (zero when nothing was). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId = :invoiceId AND p.date < :date")
    BigDecimal sumAmountByInvoiceIdPaidBefore(Long invoiceId, LocalDate date);

    /** Total paid against every invoice with any of the given statuses (zero when nothing has been paid). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p, Invoice i"
            + " WHERE p.invoiceId = i.id AND i.status IN :statuses")
    BigDecimal sumAmountByInvoiceStatusIn(Collection<InvoiceStatus> statuses);

    /** Total paid against invoices with any of the given statuses, per client; clients with none are left out. */
    @Query("SELECT pr.client.id AS clientId, SUM(p.amount) AS total FROM Payment p, Invoice i JOIN i.project pr"
            + " WHERE p.invoiceId = i.id AND i.status IN :statuses GROUP BY pr.client.id")
    List<InvoiceRepository.ClientTotal> sumAmountByInvoiceStatusInPerClient(Collection<InvoiceStatus> statuses);

    /** Total paid against invoices with any of the given statuses, per project; projects with none are left out. */
    @Query("SELECT i.project.id AS projectId, SUM(p.amount) AS total FROM Payment p, Invoice i"
            + " WHERE p.invoiceId = i.id AND i.status IN :statuses GROUP BY i.project.id")
    List<InvoiceRepository.ProjectTotal> sumAmountByInvoiceStatusInPerProject(Collection<InvoiceStatus> statuses);

    /** Total paid against each of the given invoices; invoices with no payments are left out. */
    @Query("SELECT p.invoiceId AS invoiceId, SUM(p.amount) AS paid FROM Payment p"
            + " WHERE p.invoiceId IN :invoiceIds GROUP BY p.invoiceId")
    List<InvoicePaidTotal> sumAmountByInvoiceIds(Collection<Long> invoiceIds);

    /**
     * A client's payments made against an invoice on their own (not split from a lump payment nor put toward it from
     * held deposits), each as the money received in the currency it was received in.
     */
    @Query("SELECT COALESCE(p.paidCurrency, i.currency, c.currency) AS currency, COALESCE(p.paidAmount, p.amount) AS amount,"
            + " p.date AS date FROM Payment p, Invoice i JOIN i.project pr JOIN pr.client c"
            + " WHERE p.invoiceId = i.id AND c.id = :clientId AND p.clientPaymentId IS NULL AND p.depositApplicationId IS NULL")
    List<MoneyReceived> findReceivedOnItsOwnByClientId(Long clientId);

    /** Money received on a day, in the currency it was received in. */
    interface MoneyReceived {
        String getCurrency();

        BigDecimal getAmount();

        LocalDate getDate();
    }

    /** How much has been paid against one invoice. */
    interface InvoicePaidTotal {
        Long getInvoiceId();

        BigDecimal getPaid();
    }
}
