package net.officefloor.hq.app.invoices;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @EntityGraph(attributePaths = "lines")
    List<Invoice> findByClientIdOrderByIdAsc(Long clientId);
}
