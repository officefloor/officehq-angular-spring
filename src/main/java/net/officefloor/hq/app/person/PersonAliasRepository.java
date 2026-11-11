package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonAliasRepository extends JpaRepository<PersonAlias, Long> {

    List<PersonAlias> findBySheetId(String sheetId);

    List<PersonAlias> findByPersonId(Long personId);

    boolean existsBySheetIdAndAliasKey(String sheetId, String aliasKey);

    Optional<PersonAlias> findBySheetIdAndAliasKey(String sheetId, String aliasKey);
}
