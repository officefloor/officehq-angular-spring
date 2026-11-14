package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added; a person merged into another has left it. */
    @Query("select p from Person p where p.sheetId = :sheetId and p.mergedInto is null order by p.id")
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    /** Everyone ever added to the sheet in the order they were added, those since merged into another
     *  included: a past day's view shows the people who were on the sheet then. */
    @Query("select p from Person p where p.sheetId = :sheetId order by p.id")
    List<Person> findEverBySheetId(String sheetId);

    /** The person on the sheet; a person merged into another has left it. */
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId and p.mergedInto is null")
    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The sheet's person with this name or alias, ignoring case; a person merged into another has left it. */
    @Query("select p from Person p where p.sheetId = :sheetId and p.mergedInto is null and (lower(p.name) = lower(:name)"
            + " or exists (select a from PersonAlias a where a.personId = p.id and a.aliasKey = lower(:name)))")
    Optional<Person> findByName(String sheetId, String name);

    /** Whether another person on the sheet (not {@code exceptId}) has this name, ignoring case. */
    @Query("select count(p) > 0 from Person p where p.sheetId = :sheetId and lower(p.name) = lower(:name)"
            + " and (:exceptId is null or p.id <> :exceptId)")
    boolean nameInUse(String sheetId, String name, Long exceptId);

    /** Whether another person on the sheet (not {@code exceptId}) has this contact. */
    @Query("select count(p) > 0 from Person p where p.sheetId = :sheetId and p.contact = :contact"
            + " and (:exceptId is null or p.id <> :exceptId)")
    boolean contactInUse(String sheetId, String contact, Long exceptId);
}
