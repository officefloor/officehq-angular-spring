package net.officefloor.hq.app.contact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /** A client's contacts in the order they were added. */
    List<Contact> findByClientIdOrderById(Long clientId);

    /** One of a client's contacts, if it is theirs. */
    Optional<Contact> findByIdAndClientId(Long id, Long clientId);

    /** How many contacts a client has. */
    long countByClientId(Long clientId);
}
