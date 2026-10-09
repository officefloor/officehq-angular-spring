package net.officefloor.hq.app.note;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /** A record's notes, newest first. */
    List<Note> findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(String targetType, Long targetId);
}
