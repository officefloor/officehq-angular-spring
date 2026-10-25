package net.officefloor.hq.app.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetRoleRepository extends JpaRepository<SheetRole, SheetRole.Key> {

    /** The sheet's assigned roles, by user name. */
    List<SheetRole> findBySheetIdOrderByUserNameAsc(String sheetId);

    Optional<SheetRole> findBySheetIdAndUserName(String sheetId, String userName);
}
