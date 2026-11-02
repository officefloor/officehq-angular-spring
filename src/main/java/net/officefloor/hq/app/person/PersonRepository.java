package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in list order: by their position, smallest first. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The largest position on the sheet, people merged away or transferred off included; 0 when none. */
    @Query(value = "select coalesce(max(list_position), 0) from person where sheet_id = :sheetId", nativeQuery = true)
    long maxPosition(String sheetId);

    /**
     * Bring back a person merged into another on the sheet, at the position given; returns how many were
     * brought back (0: they are not merged into that other).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update person set merged_into = null, live_name_key = name_key, list_position = :position"
            + " where id = :id and sheet_id = :sheetId and merged_into = :intoId", nativeQuery = true)
    int restoreMerged(Long id, String sheetId, Long intoId, long position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    Optional<Person> findBySheetIdAndContact(String sheetId, String contact);

    @Query("select distinct p from Person p join p.aliases a where p.sheetId = :sheetId and a.aliasKey = :aliasKey")
    Optional<Person> findBySheetIdAndAliasKey(String sheetId, String aliasKey);

    /** The person on the sheet a name or an alias of whom is the name, as names are compared. */
    default Optional<Person> findByNameOrAlias(String sheetId, String name) {
        String key = Person.keyOf(name);
        return findBySheetIdAndNameKey(sheetId, key).or(() -> findBySheetIdAndAliasKey(sheetId, key));
    }
}
