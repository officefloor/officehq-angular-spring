package net.officefloor.hq.app.note;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {

    List<Note> findBySheetIdOrderByIdDesc(String sheetId);

    List<Note> findBySheetIdAndAddedOnLessThanEqualOrderByIdDesc(String sheetId, LocalDate asOf);
}
