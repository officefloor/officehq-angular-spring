package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    /** The policy versions of a sheet, the first made first. */
    List<PolicyVersion> findBySheetIdOrderByNumber(String sheetId);

    /** How many policy versions a sheet has: the number of its current one (0: none). */
    int countBySheetId(String sheetId);
}
