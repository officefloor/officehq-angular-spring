package net.officefloor.hq.app.person;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonAliasRepository extends JpaRepository<PersonAlias, Long> {

    /** Whether a person on the sheet has an alias with this key. */
    boolean existsBySheetIdAndAliasKey(String sheetId, String aliasKey);
}
