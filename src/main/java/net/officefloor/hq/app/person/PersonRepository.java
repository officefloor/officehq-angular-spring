package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in list order: by position. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position on the sheet; 0 when it has no people. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    long maxPosition(@Param("sheetId") String sheetId);

    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The person on the sheet who has an alias with the key {@code aliasKey}. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and a.aliasKey = :aliasKey")
    Optional<Person> findBySheetIdAndAliasKey(@Param("sheetId") String sheetId, @Param("aliasKey") String aliasKey);

    /** The person on the sheet with the name, or else with an alias, {@code name} (as names are compared). */
    default Optional<Person> findBySheetIdAndNameOrAlias(String sheetId, String name) {
        String key = Person.keyOf(name);
        return findBySheetIdAndNameKey(sheetId, key).or(() -> findBySheetIdAndAliasKey(sheetId, key));
    }

    boolean existsBySheetIdInAndNameKey(Collection<String> sheetIds, String nameKey);

    /** Whether a person on one of the sheets has an alias with the key {@code aliasKey}. */
    @Query("select count(p) > 0 from Person p join p.aliases a where p.sheetId in :sheetIds and a.aliasKey = :aliasKey")
    boolean existsAliasInSheets(@Param("sheetIds") Collection<String> sheetIds, @Param("aliasKey") String aliasKey);

    Optional<Person> findBySheetIdAndContact(String sheetId, String contact);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);
}
