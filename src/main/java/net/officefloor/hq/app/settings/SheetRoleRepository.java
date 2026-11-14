package net.officefloor.hq.app.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetRoleRepository extends JpaRepository<SheetRole, Long> {

    /** The sheet's roles, by user name. */
    List<SheetRole> findBySheetIdOrderByUserAsc(String sheetId);

    Optional<SheetRole> findBySheetIdAndUser(String sheetId, String user);
}
