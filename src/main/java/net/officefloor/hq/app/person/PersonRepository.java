package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The people on a sheet in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

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

    /** The person on the sheet with the alias, compared as names are. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and lower(a) = lower(:alias)")
    Optional<Person> findByAlias(String sheetId, String alias);

    /** The person on the sheet with the name or else the alias, compared without regard to case. */
    default Optional<Person> findByNameOrAlias(String sheetId, String name) {
        return findBySheetIdAndNameIgnoreCase(sheetId, name).or(() -> findByAlias(sheetId, name));
    }
}
