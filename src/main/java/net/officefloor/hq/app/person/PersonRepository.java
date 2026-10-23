package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in list order: by their position. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position held on the sheet; 0 when it has no people. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    int findMaxPositionBySheetId(@Param("sheetId") String sheetId);

    /** The person on the sheet holding this name key, if any. */
    Optional<Person> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The person on the sheet going by this alias key, if any. */
    @Query("select distinct p from Person p join p.aliases a where p.sheetId = :sheetId and a.aliasKey = :aliasKey")
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

    /**
     * Bring back a person kept aside by a merge (see {@link Person#park}): on the sheet again under
     * their name's key, at a place in the list, showing no statement (theirs stay with the person they
     * were merged into). They are read again from the store after it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update person set merged_into = null, name_key = :nameKey, list_position = :position,
                statement_count = 0, statement_total = null, statement_date = null, version = version + 1
            where id = :id and merged_into is not null""", nativeQuery = true)
    int restore(@Param("id") long id, @Param("nameKey") String nameKey, @Param("position") int position);

    /** The name of a person kept aside by a merge; empty when there is none such. */
    @Query(value = "select name from person where id = :id and merged_into is not null", nativeQuery = true)
    Optional<String> findParkedName(@Param("id") long id);

    /** The aliases, as written, of a person kept aside by a merge. */
    @Query(value = "select a.alias from person_alias a join person p on p.id = a.person_id where p.id = :id and p.merged_into is not null",
            nativeQuery = true)
    List<String> findParkedAliases(@Param("id") long id);
}
