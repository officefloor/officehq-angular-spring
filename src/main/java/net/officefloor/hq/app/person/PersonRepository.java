package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

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

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);
}
