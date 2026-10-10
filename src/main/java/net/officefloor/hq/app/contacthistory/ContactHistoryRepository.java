package net.officefloor.hq.app.contacthistory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactHistoryRepository extends JpaRepository<ContactHistoryEntry, Long> {

    /** A client's contact history, newest first; same-day entries latest-recorded first. */
    List<ContactHistoryEntry> findByClientIdOrderByDateDescIdDesc(Long clientId);
}
