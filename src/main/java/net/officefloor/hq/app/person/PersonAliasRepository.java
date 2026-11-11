package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonAliasRepository extends JpaRepository<PersonAlias, Long> {

    List<PersonAlias> findBySheetId(String sheetId);

    List<PersonAlias> findByPersonId(Long personId);

    boolean existsBySheetIdAndAliasKey(String sheetId, String aliasKey);

    Optional<PersonAlias> findBySheetIdAndAliasKey(String sheetId, String aliasKey);

    /** Whether an alias on any of the sheets, of a person other than the one with this id, has the key. */
    boolean existsBySheetIdInAndAliasKeyAndPersonIdNot(Collection<String> sheetIds, String aliasKey, Long personId);

    /** The alias keys on the sheets. */
    @Query("select a.aliasKey from PersonAlias a where a.sheetId in :sheetIds")
    List<String> findAliasKeysBySheetIdIn(Collection<String> sheetIds);
}
