package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** Everyone on the sheet, in list order. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position taken on the sheet; 0 when it has no one. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    int findMaxPositionBySheetId(@Param("sheetId") String sheetId);

    /** Put the person at {@code position} in the list, leaving their version as it is: a move is no edit of them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Person p set p.position = :position where p.id = :id")
    void setPosition(@Param("id") Long id, @Param("position") int position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    Optional<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);

    /** The person on the sheet having {@code alias} as an alias, without regard to case. */
    @Query("select p from Person p join p.aliases a where p.sheetId = :sheetId and lower(a) = lower(:alias)")
    Optional<Person> findBySheetIdAndAliasIgnoreCase(@Param("sheetId") String sheetId, @Param("alias") String alias);

    /** The person on the sheet named {@code name}, or else having it as an alias, without regard to case. */
    default Optional<Person> findBySheetIdAndNameOrAliasIgnoreCase(String sheetId, String name) {
        return findBySheetIdAndNameIgnoreCase(sheetId, name).or(() -> findBySheetIdAndAliasIgnoreCase(sheetId, name));
    }

    /** The names and aliases used on any of the sheets, lower-cased. */
    @Query("select lower(p.name) from Person p where p.sheetId in :sheetIds")
    List<String> findLowerNamesBySheetIdIn(@Param("sheetIds") List<String> sheetIds);

    /** The aliases used on any of the sheets, lower-cased. */
    @Query("select lower(a) from Person p join p.aliases a where p.sheetId in :sheetIds")
    List<String> findLowerAliasesBySheetIdIn(@Param("sheetIds") List<String> sheetIds);

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);
}
