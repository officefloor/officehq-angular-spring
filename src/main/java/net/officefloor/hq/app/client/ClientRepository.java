package net.officefloor.hq.app.client;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /** All clients not archived, in id order. */
    List<Client> findByArchivedFalseOrderById();
}
