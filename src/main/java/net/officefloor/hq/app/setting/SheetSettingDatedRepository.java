package net.officefloor.hq.app.setting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSettingDatedRepository extends JpaRepository<SheetSettingDated, SheetSettingDated.Key> {

    List<SheetSettingDated> findBySheetIdOrderByKeyAscFromAsc(String sheetId);

    Optional<SheetSettingDated> findBySheetIdAndKeyAndFrom(String sheetId, String key, LocalDate from);
}
