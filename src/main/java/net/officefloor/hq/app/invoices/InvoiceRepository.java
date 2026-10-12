package net.officefloor.hq.app.invoices;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @EntityGraph(attributePaths = "lines")
    List<Invoice> findByClientIdOrderByIdAsc(Long clientId);

    @EntityGraph(attributePaths = "lines")
    List<Invoice> findAllByOrderByIdAsc();

    @EntityGraph(attributePaths = "lines")
    Optional<Invoice> findWithLinesById(Long id);

    /** The ids of the invoices not written off that have had at least the given number of reminders, lowest first. */
    @Query("SELECT i.id FROM Invoice i WHERE i.writtenOffOn IS NULL AND SIZE(i.reminders) >= :reminders ORDER BY i.id")
    List<Long> findNotWrittenOffWithReminders(@Param("reminders") int reminders);

    /** Whether a draft has been generated from the recurring invoice as its next period. */
    boolean existsByRecurringFrom(Long recurringFrom);

    /** Whether the next progress claim has been made from the invoice. */
    boolean existsByClaimFrom(Long claimFrom);
}
