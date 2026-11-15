package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in list order: by position (the order they were added, unless moved). */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The position after the last person's on the sheet: where a new person goes. */
    @Query("select coalesce(max(p.position), 0) + 1 from Person p where p.sheetId = :sheetId")
    long nextPosition(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The sheet's people with this name, ignoring case. */
    List<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** The sheet's people with this alias, ignoring case. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and lower(a) = lower(:alias)")
    List<Person> findBySheetIdAndAliasIgnoreCase(String sheetId, String alias);

    /** The sheet's person with this name, else with this alias, ignoring case. */
    default Optional<Person> findBySheetIdAndNameOrAlias(String sheetId, String name) {
        return findBySheetIdAndNameIgnoreCase(sheetId, name).stream().findFirst()
                .or(() -> findBySheetIdAndAliasIgnoreCase(sheetId, name).stream().findFirst());
    }

    /** The sheet's people with this contact. */
    List<Person> findBySheetIdAndContact(String sheetId, String contact);

    /** The person, locked so two statements of them are taken one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> findForUpdate(Long id, String sheetId);

    /** The person set aside by a merge into another that still stands (found by no other query). */
    @Query(value = "SELECT * FROM person WHERE id = :id AND sheet_id = :sheetId AND merged_into = :intoId FOR UPDATE",
            nativeQuery = true)
    Optional<Person> findMergedForUpdate(Long id, String sheetId, Long intoId);
}
