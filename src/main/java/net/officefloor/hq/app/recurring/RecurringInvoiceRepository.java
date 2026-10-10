package net.officefloor.hq.app.recurring;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringInvoiceRepository extends JpaRepository<RecurringInvoice, Long> {

    /** A project's recurring invoices, soonest next first. */
    List<RecurringInvoice> findByProjectIdOrderByNextDateAscIdAsc(Long projectId);

    /** Every recurring invoice in the given status next falling on or after the given day, soonest first. */
    List<RecurringInvoice> findByStatusAndNextDateGreaterThanEqualOrderByNextDateAscIdAsc(RecurringStatus status,
            LocalDate from);
}
