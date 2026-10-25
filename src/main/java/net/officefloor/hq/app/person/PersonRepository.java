package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person, locked until the transaction ends (without bumping their version), so two
     *  statements of the same person are taken one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> findForUpdate(@Param("id") Long id, @Param("sheetId") String sheetId);

    /** Whether a person on the sheet, other than the one with this id, has this name key. */
    boolean existsBySheetIdAndNameKeyAndIdNot(String sheetId, String nameKey, Long id);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The person on the sheet with this name key, if any. */
    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);
}
