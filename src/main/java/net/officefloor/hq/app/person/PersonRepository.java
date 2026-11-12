package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** A sheet's people in list order: by position, then in the order they were added. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position on the sheet; 0 when it has no people. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    long maxPosition(String sheetId);

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

    /** Bring a person kept aside by a merge back onto their sheet, at the position, under their own name again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE person SET merged_away = FALSE, name_key = :nameKey, position = :position,"
            + " version = version + 1 WHERE id = :id AND sheet_id = :sheetId AND merged_away", nativeQuery = true)
    int bringBack(Long id, String sheetId, String nameKey, long position);

    /** The name of a person kept aside by a merge; empty when there is no such person. */
    @Query(value = "SELECT name FROM person WHERE id = :id AND sheet_id = :sheetId AND merged_away", nativeQuery = true)
    Optional<String> findMergedAwayName(Long id, String sheetId);
}
