package net.officefloor.hq.app.settings;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSettingRepository extends JpaRepository<SheetSetting, Long> {

    List<SheetSetting> findBySheetIdAndKeyOrderByFromAsc(String sheetId, String key);

    Optional<SheetSetting> findBySheetIdAndKeyAndFrom(String sheetId, String key, LocalDate from);

    /** The value recorded with the latest from-date on or before {@code date}. */
    Optional<SheetSetting> findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(String sheetId, String key,
            LocalDate date);
}
