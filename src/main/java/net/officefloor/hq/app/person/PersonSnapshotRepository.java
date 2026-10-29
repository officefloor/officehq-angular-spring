package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The snapshot lines of the people on the sheet, oldest first. */
    @Query("SELECT s FROM PersonSnapshot s WHERE s.personId IN (SELECT p.id FROM Person p WHERE p.sheetId = :sheetId)"
            + " ORDER BY s.id")
    List<PersonSnapshot> findOfSheet(@Param("sheetId") String sheetId);
}
