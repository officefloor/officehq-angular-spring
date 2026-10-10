package net.officefloor.hq.app.note;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /** A record's notes, newest first. */
    List<Note> findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(String targetType, Long targetId);

    /** Moves every note of one record over to another record of the same type. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Note n SET n.targetId = :to WHERE n.targetType = :targetType AND n.targetId = :from")
    int moveNotes(String targetType, Long from, Long to);
}
