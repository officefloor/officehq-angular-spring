package net.officefloor.hq.app.invoice;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByProjectIdOrderById(Long projectId);
}
