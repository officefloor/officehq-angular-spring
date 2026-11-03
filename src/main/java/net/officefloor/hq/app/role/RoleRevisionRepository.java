package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRevisionRepository extends JpaRepository<RoleRevision, Long> {

    /** The last role given to a user name on or before a day. */
    Optional<RoleRevision> findFirstBySheetIdAndUserNameAndRecordedOnLessThanEqualOrderByRecordedOnDescIdDesc(
            String sheetId, String userName, LocalDate day);

    /** The roles given on a sheet on or before a day, in the order they were given. */
    List<RoleRevision> findBySheetIdAndRecordedOnLessThanEqualOrderByRecordedOnAscIdAsc(String sheetId, LocalDate day);
}
