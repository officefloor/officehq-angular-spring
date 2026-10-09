package net.officefloor.hq.app.client;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /** All clients not archived, in id order. */
    List<Client> findByArchivedFalseOrderById();

    /** Clients not archived whose name contains the text, ignoring case, in id order. */
    @Query("SELECT c FROM Client c WHERE c.archived = false AND LOWER(c.name) LIKE LOWER(CONCAT('%', :text, '%'))"
            + " ESCAPE '\\' ORDER BY c.id")
    List<Client> searchActiveByName(String text);
}
