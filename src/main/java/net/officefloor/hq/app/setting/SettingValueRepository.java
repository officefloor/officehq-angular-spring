package net.officefloor.hq.app.setting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingValueRepository extends JpaRepository<SettingValue, Long> {

    List<SettingValue> findBySheetIdOrderByKeyAscFromAsc(String sheetId);

    Optional<SettingValue> findBySheetIdAndKeyAndFrom(String sheetId, String key, LocalDate from);

    /** The value in force on a date: the one with the latest from-date on or before it. */
    Optional<SettingValue> findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(String sheetId, String key,
            LocalDate on);
}
