package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person on the sheet holding this name key, if any. */
    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The person on the sheet going by this alias key, if any. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and a.aliasKey = :aliasKey")
    Optional<Person> findBySheetIdAndAliasKey(@Param("sheetId") String sheetId, @Param("aliasKey") String aliasKey);

    /** The person on the sheet going by this name or alias, compared as names are, if any. */
    default Optional<Person> findBySheetIdAndName(String sheetId, String name) {
        String key = Person.keyOf(name);
        Optional<Person> named = findBySheetIdAndNameKey(sheetId, key);
        return named.isPresent() ? named : findBySheetIdAndAliasKey(sheetId, key);
    }

    /** The name keys of the people on these sheets. */
    @Query("select p.nameKey from Person p where p.sheetId in :sheetIds")
    List<String> findNameKeysBySheetIdIn(@Param("sheetIds") Collection<String> sheetIds);

    /** The alias keys of the people on these sheets. */
    @Query("select a.aliasKey from Person p join p.aliases a where p.sheetId in :sheetIds")
    List<String> findAliasKeysBySheetIdIn(@Param("sheetIds") Collection<String> sheetIds);

    /** The person on the sheet holding this contact, if any. */
    Optional<Person> findBySheetIdAndContact(String sheetId, String contact);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);
}
