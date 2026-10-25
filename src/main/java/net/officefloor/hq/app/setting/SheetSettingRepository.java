package net.officefloor.hq.app.setting;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSettingRepository extends JpaRepository<SheetSetting, SheetSetting.Key> {

    List<SheetSetting> findBySheetId(String sheetId);

    Optional<SheetSetting> findBySheetIdAndKey(String sheetId, String key);
}
