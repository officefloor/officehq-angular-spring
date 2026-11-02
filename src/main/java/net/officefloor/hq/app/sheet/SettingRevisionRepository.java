package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRevisionRepository extends JpaRepository<SettingRevision, Long> {

    /** The setting revisions of a sheet in the order they were recorded. */
    List<SettingRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
