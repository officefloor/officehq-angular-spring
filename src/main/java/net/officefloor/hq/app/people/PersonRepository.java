package net.officefloor.hq.app.people;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The people on a sheet; a null sheet id is the unnamed sheet. */
    List<Person> findBySheetId(String sheetId, Sort sort);

    /** The person with an id on a sheet; a null sheet id is the unnamed sheet. */
    Optional<Person> findBySheetIdAndLocalId(String sheetId, Long localId);

    /** The highest person id on a sheet, or 0 when it has nobody; a null sheet id is the unnamed sheet. */
    @Query("SELECT COALESCE(MAX(p.localId), 0) FROM Person p "
            + "WHERE (:sheetId IS NULL AND p.sheetId IS NULL) OR p.sheetId = :sheetId")
    long maxLocalId(@Param("sheetId") String sheetId);
}
