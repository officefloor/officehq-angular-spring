package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRevisionRepository extends JpaRepository<RoleRevision, Long> {

    /** The last role given to a user name on or before a day. */
    Optional<RoleRevision> findFirstBySheetIdAndUserNameAndRecordedOnLessThanEqualOrderByRecordedOnDescIdDesc(
            String sheetId, String userName, LocalDate day);
}
