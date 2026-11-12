package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person on the sheet with the name, ignoring case. */
    @Query("select p from Person p where p.sheetId = :sheetId and lower(p.name) = lower(:name)")
    Optional<Person> findByName(String sheetId, String name);

    /** The person on the sheet with the name or an alias equal to it, ignoring case. */
    @Query("select distinct p from Person p left join p.aliases a where p.sheetId = :sheetId"
            + " and (lower(p.name) = lower(:name) or lower(a.alias) = lower(:name))")
    Optional<Person> findByNameOrAlias(String sheetId, String name);

    /** Whether someone on the sheet other than {@code exceptId} has the name, ignoring case; or anyone on
     *  the sheet has it as an alias. */
    @Query("select count(p) > 0 from Person p where p.sheetId = :sheetId and (lower(p.name) = lower(:name)"
            + " and (:exceptId is null or p.id <> :exceptId)"
            + " or exists (select a from PersonAlias a where a.sheetId = :sheetId and lower(a.alias) = lower(:name)))")
    boolean nameTaken(String sheetId, String name, Long exceptId);

    /** Whether someone on the sheet other than {@code exceptId} has the contact. */
    @Query("select count(p) > 0 from Person p where p.sheetId = :sheetId and p.contact = :contact"
            + " and (:exceptId is null or p.id <> :exceptId)")
    boolean contactTaken(String sheetId, String contact, Long exceptId);

    /** Everyone on the sheets. */
    List<Person> findBySheetIdIn(Collection<String> sheetIds);
}
