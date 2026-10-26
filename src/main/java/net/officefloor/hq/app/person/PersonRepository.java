package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

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

    boolean existsBySheetIdAndContact(String sheetId, String contact);

    boolean existsBySheetIdAndContactAndIdNot(String sheetId, String contact, Long id);
}
