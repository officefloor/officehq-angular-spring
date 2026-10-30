package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSettingRepository extends JpaRepository<SheetSetting, Long> {

    List<SheetSetting> findBySheetId(String sheetId);

    /** The key's value with this from-date. */
    Optional<SheetSetting> findBySheetIdAndKeyAndFromDate(String sheetId, String key, LocalDate fromDate);
}
