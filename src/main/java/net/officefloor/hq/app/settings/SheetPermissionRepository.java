package net.officefloor.hq.app.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPermissionRepository extends JpaRepository<SheetPermission, Long> {

    List<SheetPermission> findBySheetId(String sheetId);

    Optional<SheetPermission> findBySheetIdAndAction(String sheetId, Action action);
}
