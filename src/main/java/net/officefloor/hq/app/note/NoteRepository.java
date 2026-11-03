package net.officefloor.hq.app.note;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /** The notes of a sheet, newest first. */
    List<Note> findBySheetIdOrderByIdDesc(String sheetId);
}
