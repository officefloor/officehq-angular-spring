package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPermissionRepository extends JpaRepository<SheetPermission, SheetPermission.Key> {

    /** The permissions recorded on a sheet. */
    List<SheetPermission> findBySheetId(String sheetId);
}
