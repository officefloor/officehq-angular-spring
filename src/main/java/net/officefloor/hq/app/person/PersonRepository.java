package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The people on the sheet in list order: by position, then as they were added. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The highest position on the sheet; 0 when it has no one. */
    @Query("select coalesce(max(p.position), 0) from Person p where p.sheetId = :sheetId")
    int maxPosition(@Param("sheetId") String sheetId);

    /** Set the person's position, leaving their version as it is: a move is no edit of them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Person p set p.position = :position where p.id = :id")
    void setPosition(@Param("id") Long id, @Param("position") int position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    Optional<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);

    /** The person on the sheet with the name or an alias, compared without regard to case. */
    @Query("select distinct p from Person p left join p.aliases a where p.sheetId = :sheetId"
            + " and (lower(p.name) = lower(:name) or lower(a) = lower(:name))")
    List<Person> findBySheetIdAndNameOrAlias(@Param("sheetId") String sheetId, @Param("name") String name);

    /** Whether a person on the sheet, other than the one with the id (none: any), has the alias, compared without regard to case. */
    @Query("select count(p) > 0 from Person p join p.aliases a where p.sheetId = :sheetId"
            + " and lower(a) = lower(:alias) and (:id is null or p.id <> :id)")
    boolean existsAlias(@Param("sheetId") String sheetId, @Param("alias") String alias, @Param("id") Long id);

    List<Person> findBySheetIdIn(Collection<String> sheetIds);

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);
}
