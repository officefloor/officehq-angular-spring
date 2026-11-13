package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The sheet's person with this name, ignoring case. */
    @Query("select p from Person p where p.sheetId = :sheetId and lower(p.name) = lower(:name)")
    Optional<Person> findByName(String sheetId, String name);

    /** Whether another person on the sheet (not {@code exceptId}) has this name, ignoring case. */
    @Query("select count(p) > 0 from Person p where p.sheetId = :sheetId and lower(p.name) = lower(:name)"
            + " and (:exceptId is null or p.id <> :exceptId)")
    boolean nameInUse(String sheetId, String name, Long exceptId);
}
