package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in list order: by their place, one who was never moved in their id's place. */
    @Query("select p from Person p where p.sheetId = :sheetId order by coalesce(p.position, p.id), p.id")
    List<Person> findInListOrder(@Param("sheetId") String sheetId);

    /** Put the person in a place in the list, without bumping their version. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Person p set p.position = :position where p.id = :id")
    int placeAt(@Param("id") Long id, @Param("position") long position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The last place in the sheet's list; 0 when it has no one. */
    @Query("select coalesce(max(coalesce(p.position, p.id)), 0) from Person p where p.sheetId = :sheetId")
    long lastPlace(@Param("sheetId") String sheetId);

    /** A person a merge took off their sheet, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId is null")
    Optional<Person> findParkedForUpdate(@Param("id") Long id);

    /** The person, locked until the transaction ends (without bumping their version), so two
     *  statements of the same person are taken one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Person p where p.id = :id and p.sheetId = :sheetId")
    Optional<Person> findForUpdate(@Param("id") Long id, @Param("sheetId") String sheetId);

    /** Whether a person on the sheet, other than the one with this id, has this name key. */
    boolean existsBySheetIdAndNameKeyAndIdNot(String sheetId, String nameKey, Long id);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);

    /** Whether a person on the sheet, other than the one with this id, has this contact. */
    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    /** The person on the sheet with this name key, if any. */
    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The person on the sheet with this key as their name or as one of their aliases, if any. */
    @Query("select distinct p from Person p left join p.aliases a where p.sheetId = :sheetId"
            + " and (p.nameKey = :key or a.aliasKey = :key)")
    Optional<Person> findByNameOrAlias(@Param("sheetId") String sheetId, @Param("key") String key);

    /** The name keys of the people on the sheets, other than the one with this id. */
    @Query("select p.nameKey from Person p where p.sheetId in :sheetIds and p.id <> :exceptId")
    List<String> nameKeysOn(@Param("sheetIds") Collection<String> sheetIds, @Param("exceptId") Long exceptId);

    /** The alias keys of the people on the sheets, other than the one with this id. */
    @Query("select a.aliasKey from Person p join p.aliases a where p.sheetId in :sheetIds and p.id <> :exceptId")
    List<String> aliasKeysOn(@Param("sheetIds") Collection<String> sheetIds, @Param("exceptId") Long exceptId);
}
