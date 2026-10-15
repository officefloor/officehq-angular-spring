package net.officefloor.hq.app.people;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonGroupRepository extends JpaRepository<PersonGroup, Long> {

    /** The groups on a sheet; a null sheet id is the unnamed sheet. */
    List<PersonGroup> findBySheetId(String sheetId, Sort sort);

    /** The group with an id on a sheet; a null sheet id is the unnamed sheet. */
    Optional<PersonGroup> findBySheetIdAndLocalId(String sheetId, Long localId);

    /** The highest group id on a sheet, or 0 when it has none; a null sheet id is the unnamed sheet. */
    @Query("SELECT COALESCE(MAX(g.localId), 0) FROM PersonGroup g "
            + "WHERE (:sheetId IS NULL AND g.sheetId IS NULL) OR g.sheetId = :sheetId")
    long maxLocalId(@Param("sheetId") String sheetId);
}
