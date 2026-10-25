package net.officefloor.hq.app.setting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingChangeRepository extends JpaRepository<SettingChange, Long> {

    /** The sheet's dated-setting changes in the order they were made. */
    List<SettingChange> findBySheetIdOrderByIdAsc(String sheetId);
}
