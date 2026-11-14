package net.officefloor.hq.app.person;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PersonAliasRepository extends JpaRepository<PersonAlias, Long> {

    /** The sheet's aliases in the order they were added. */
    List<PersonAlias> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's aliases in the order they were added. */
    List<PersonAlias> findByPersonIdOrderByIdAsc(Long personId);

    /** Whether any person on the sheet has this alias, ignoring case. */
    @Query("select count(a) > 0 from PersonAlias a where a.sheetId = :sheetId and a.aliasKey = lower(:alias)")
    boolean aliasInUse(String sheetId, String alias);

    /** Whether anyone on any of the sheets has this alias, ignoring case. */
    @Query("select count(a) > 0 from PersonAlias a where a.sheetId in :sheetIds and a.aliasKey = lower(:alias)")
    boolean aliasInUseOn(Collection<String> sheetIds, String alias);

    /** The aliases on any of the sheets, lower-cased. */
    @Query("select a.aliasKey from PersonAlias a where a.sheetId in :sheetIds")
    List<String> aliasKeysOn(Collection<String> sheetIds);
}
