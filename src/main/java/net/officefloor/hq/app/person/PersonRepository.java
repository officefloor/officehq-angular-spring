package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The people on a sheet in list order: by position, then in the order they were added. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The position after the last on the sheet's list: where a new person goes. */
    @Query("select coalesce(max(p.position), 0) + 1 from Person p where p.sheetId = :sheetId")
    long nextPosition(String sheetId);

    /** Put a person at a position in the list. Moving a person is no edit of them: their version
     *  stays as it is. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Person p set p.position = :position where p.id = :id")
    void placeAt(Long id, long position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** Put a person who left the sheet back on it under the id they had, with no grade yet (see
     *  {@link PersonMergeService#undo}). */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into person (id, sheet_id, name, position, version, terms, contact, tags, origin,"
            + " arrived_on, flag_reason, flag_until, approver) values (:id, :sheetId, :name, :position, 0, :terms,"
            + " :contact, :tags, :origin, :arrivedOn, :flagReason, :flagUntil, :approver)", nativeQuery = true)
    void restore(Long id, String sheetId, String name, long position, Integer terms, String contact, String tags,
            String origin, LocalDate arrivedOn, String flagReason, LocalDate flagUntil, String approver);

    /** A person on a sheet, locked for the rest of the transaction so two statements of them are
     *  taken one after the other. Locking does not raise their version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> lockByIdAndSheetId(Long id, String sheetId);

    /** Whether the name is in use on the sheet, compared without regard to case. */
    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** The person on the sheet with the name, compared without regard to case. */
    Optional<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether the contact is held on the sheet. */
    boolean existsBySheetIdAndContact(String sheetId, String contact);

    /** Whether the contact is held on the sheet by anyone but this person. */
    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);

    /** Whether the name is in use on the sheet by anyone but this person. */
    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);

    /** Whether the text is an alias of anyone on the sheet, compared as names are. */
    @Query("select count(p) > 0 from Person p join p.aliases a where p.sheetId = :sheetId"
            + " and lower(a) = lower(:alias)")
    boolean existsAlias(String sheetId, String alias);

    /** The names of everyone on the sheets, lower-cased. */
    @Query("select lower(p.name) from Person p where p.sheetId in :sheetIds")
    List<String> namesOn(Collection<String> sheetIds);

    /** The aliases of everyone on the sheets, lower-cased. */
    @Query("select lower(a) from Person p join p.aliases a where p.sheetId in :sheetIds")
    List<String> aliasesOn(Collection<String> sheetIds);

    /** The person on the sheet with the alias, compared as names are. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and lower(a) = lower(:alias)")
    Optional<Person> findByAlias(String sheetId, String alias);

    /** The person on the sheet with the name or else the alias, compared without regard to case. */
    default Optional<Person> findByNameOrAlias(String sheetId, String name) {
        return findBySheetIdAndNameIgnoreCase(sheetId, name).or(() -> findByAlias(sheetId, name));
    }
}
