package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** A sheet's people in list order: by position, ties in the order they were added. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position on the sheet, or 0 when it has no one. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    long maxPosition(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person, locked until the transaction ends, so their statements are taken one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> lockByIdAndSheetId(Long id, String sheetId);

    /** Whether another person on the sheet has this name, compared without regard to case. */
    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a person on the sheet has this contact. */
    boolean existsBySheetIdAndContact(String sheetId, String contact);

    /** Whether another person on the sheet has this contact. */
    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);

    /** The person on the sheet with this name, compared without regard to case. */
    Optional<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a person on the sheet has this alias, compared without regard to case. */
    @Query("select count(p) > 0 from Person p join p.aliases a where p.sheetId = :sheetId and lower(a) = lower(:alias)")
    boolean existsAliasOnSheet(String sheetId, String alias);

    /** The person on the sheet with this name or alias, compared without regard to case. */
    @Query("select distinct p from Person p left join p.aliases a where p.sheetId = :sheetId"
            + " and (lower(p.name) = lower(:name) or lower(a) = lower(:name))")
    Optional<Person> findBySheetIdAndNameOrAlias(String sheetId, String name);

    /** The people on any of the sheets. */
    List<Person> findBySheetIdIn(Collection<String> sheetIds);

    /**
     * Whether a person on any of the sheets, other than the one of {@code exceptId}, has this name or
     * alias, compared without regard to case.
     */
    @Query("select count(p) > 0 from Person p left join p.aliases a where p.sheetId in :sheetIds and p.id <> :exceptId"
            + " and (lower(p.name) = lower(:name) or lower(a) = lower(:name))")
    boolean existsNameOrAliasOnSheets(Collection<String> sheetIds, String name, Long exceptId);
}
