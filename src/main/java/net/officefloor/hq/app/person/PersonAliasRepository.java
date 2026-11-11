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

    /** The first alias on the sheet with the key (one joined by a transfer may share it). */
    Optional<PersonAlias> findFirstBySheetIdAndAliasKeyOrderByIdAsc(String sheetId, String aliasKey);

    /** The next free slot for the key on the sheet: 0 when no alias has it. */
    @Query("select coalesce(max(a.aliasSlot) + 1, 0) from PersonAlias a where a.sheetId = :sheetId and a.aliasKey = :aliasKey")
    int nextSlot(String sheetId, String aliasKey);

    /** Whether an alias on any of the sheets, of a person other than the one with this id, has the key. */
    boolean existsBySheetIdInAndAliasKeyAndPersonIdNot(Collection<String> sheetIds, String aliasKey, Long personId);

    /** The alias keys on the sheets. */
    @Query("select a.aliasKey from PersonAlias a where a.sheetId in :sheetIds")
    List<String> findAliasKeysBySheetIdIn(Collection<String> sheetIds);
}
