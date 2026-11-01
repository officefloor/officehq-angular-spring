package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonRepository extends JpaRepository<Person, Long> {

    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    Optional<Person> findBySheetIdAndContact(String sheetId, String contact);

    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and a.aliasKey = :aliasKey")
    Optional<Person> findBySheetIdAndAliasKey(String sheetId, String aliasKey);

    /** The person on the sheet a name or an alias of whom is the name, as names are compared. */
    default Optional<Person> findByNameOrAlias(String sheetId, String name) {
        String key = Person.keyOf(name);
        return findBySheetIdAndNameKey(sheetId, key).or(() -> findBySheetIdAndAliasKey(sheetId, key));
    }
}
