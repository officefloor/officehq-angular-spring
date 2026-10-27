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

    /** The people on the sheet, in list order. */
    List<Person> findBySheetIdOrderByPositionAscIdAsc(String sheetId);

    /** The position after the last person's on the sheet: where a new person goes. */
    @Query("SELECT COALESCE(MAX(p.position), 0) + 1 FROM Person p WHERE p.sheetId = :sheetId")
    long nextPosition(@Param("sheetId") String sheetId);

    /** Puts the person at the position, leaving their version as it is: a place in the list is not an edit. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Person p SET p.position = :position WHERE p.id = :id")
    void placeAt(@Param("id") Long id, @Param("position") long position);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person, locked until the transaction ends, so two statements of them never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Person> findWithLockByIdAndSheetId(Long id, String sheetId);

    Optional<Person> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);

    /** The person on the sheet with the alias, compared as names are: ignoring case. */
    @Query("SELECT p FROM Person p JOIN p.aliases a WHERE p.sheetId = :sheetId AND LOWER(a.alias) = LOWER(:alias)")
    Optional<Person> findBySheetIdAndAlias(@Param("sheetId") String sheetId, @Param("alias") String alias);

    /** Whether the name or alias is a name or an alias of anyone on the sheet, compared ignoring case. */
    default boolean nameOrAliasInUse(String sheetId, String nameOrAlias) {
        return existsBySheetIdAndNameIgnoreCase(sheetId, nameOrAlias)
                || findBySheetIdAndAlias(sheetId, nameOrAlias).isPresent();
    }

    /** The person on the sheet with the name, or else with the alias, both compared ignoring case. */
    default Optional<Person> findBySheetIdAndNameOrAlias(String sheetId, String nameOrAlias) {
        return findBySheetIdAndNameIgnoreCase(sheetId, nameOrAlias)
                .or(() -> findBySheetIdAndAlias(sheetId, nameOrAlias));
    }

    /** Whether the name or alias is a name or an alias of anyone on any of the sheets, compared ignoring case. */
    @Query("SELECT COUNT(p) > 0 FROM Person p WHERE p.sheetId IN :sheetIds AND (LOWER(p.name) = LOWER(:nameOrAlias)"
            + " OR EXISTS (SELECT a FROM p.aliases a WHERE LOWER(a.alias) = LOWER(:nameOrAlias)))")
    boolean nameOrAliasInUseOn(@Param("sheetIds") Collection<String> sheetIds,
            @Param("nameOrAlias") String nameOrAlias);

    /** The people on any of the sheets. */
    List<Person> findBySheetIdIn(Collection<String> sheetIds);

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);
}
