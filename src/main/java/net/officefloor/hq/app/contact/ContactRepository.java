package net.officefloor.hq.app.contact;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /** A client's contacts in the order they were added. */
    List<Contact> findByClientIdOrderById(Long clientId);

    /** How many contacts a client has. */
    long countByClientId(Long clientId);
}
