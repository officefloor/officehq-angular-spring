package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingValueRepository extends JpaRepository<SettingValue, SettingValue.Key> {

    /** The value of a setting in force on a day: the one with the latest from-date on or before it. */
    Optional<SettingValue> findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(String sheetId, String key,
            LocalDate day);

    /** The values recorded for a setting, earliest from-date first. */
    List<SettingValue> findBySheetIdAndKeyOrderByFrom(String sheetId, String key);
}
