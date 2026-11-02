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

    /** Whether the name is in use on the sheet by anyone but this person. */
    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);
}
