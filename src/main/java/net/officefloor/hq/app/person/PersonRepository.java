package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** A sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person, locked until the transaction ends, so two statements of them do not interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> findForUpdate(Long id, String sheetId);

    /** Whether a person on the sheet, other than the one with this id, has the name key. */
    boolean existsBySheetIdAndNameKeyAndIdNot(String sheetId, String nameKey, Long id);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);

    /** Whether a person on the sheet, other than the one with this id, has the contact. */
    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** Whether a person on any of the sheets, other than the one with this id, has the name key. */
    boolean existsBySheetIdInAndNameKeyAndIdNot(Collection<String> sheetIds, String nameKey, Long id);

    /** The name keys of the people on the sheets. */
    @Query("select p.nameKey from Person p where p.sheetId in :sheetIds")
    List<String> findNameKeysBySheetIdIn(Collection<String> sheetIds);
}
