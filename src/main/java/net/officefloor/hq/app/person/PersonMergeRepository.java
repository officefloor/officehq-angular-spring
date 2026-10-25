package net.officefloor.hq.app.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonMergeRepository extends JpaRepository<PersonMerge, Long> {

    /** The last merge made into the person on the sheet. */
    Optional<PersonMerge> findFirstBySheetIdAndIntoIdOrderByIdDesc(String sheetId, Long intoId);

    /** Puts back the row of a person merged away, under their own id, with their name and place in the
     * list; the rest of them is given back through the entity (see {@link Person#restoreFrom}). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = "INSERT INTO person (id, sheet_id, name, name_key, list_position, version)"
            + " VALUES (:id, :sheetId, :name, :nameKey, :position, 0)", nativeQuery = true)
    int restorePerson(@Param("id") Long id, @Param("sheetId") String sheetId, @Param("name") String name,
            @Param("nameKey") String nameKey, @Param("position") long position);
}
