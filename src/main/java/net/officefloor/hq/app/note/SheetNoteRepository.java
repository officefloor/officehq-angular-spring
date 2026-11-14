package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetNoteRepository extends JpaRepository<SheetNote, Long> {

    /** The sheet's notes, the newest first. */
    List<SheetNote> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's notes added while its today was on or before a date, the newest first. */
    List<SheetNote> findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(String sheetId, LocalDate asOf);
}
