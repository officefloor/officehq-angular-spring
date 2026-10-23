package net.officefloor.hq.app.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleChangeRepository extends JpaRepository<RoleChange, Long> {

    /** The roles set on the sheet, in the order they were set. */
    List<RoleChange> findBySheetIdOrderByIdAsc(String sheetId);
}
