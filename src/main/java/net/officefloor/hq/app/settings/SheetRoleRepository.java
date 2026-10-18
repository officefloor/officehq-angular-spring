package net.officefloor.hq.app.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetRoleRepository extends JpaRepository<SheetRole, Long> {

    List<SheetRole> findBySheetIdOrderByUserNameAsc(String sheetId);

    Optional<SheetRole> findBySheetIdAndUserName(String sheetId, String userName);
}
