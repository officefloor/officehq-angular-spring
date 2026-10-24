package net.officefloor.hq.app.note;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetNoteRepository extends JpaRepository<SheetNote, Long> {

    List<SheetNote> findBySheetIdOrderByIdDesc(String sheetId);
}
