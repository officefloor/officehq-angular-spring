package net.officefloor.hq.app.recurring;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringInvoiceRepository extends JpaRepository<RecurringInvoice, Long> {

    /** A project's recurring invoices, soonest next first. */
    List<RecurringInvoice> findByProjectIdOrderByNextDateAscIdAsc(Long projectId);
}
