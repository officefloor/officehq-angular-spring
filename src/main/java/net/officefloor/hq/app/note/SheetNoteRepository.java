package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetNoteRepository extends JpaRepository<SheetNote, Long> {

    List<SheetNote> findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(String sheetId, LocalDate day);

    List<SheetNote> findBySheetIdOrderByIdDesc(String sheetId);
}
