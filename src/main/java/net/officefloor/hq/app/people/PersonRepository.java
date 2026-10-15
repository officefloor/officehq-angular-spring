package net.officefloor.hq.app.people;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The people on a sheet; a null sheet id is the unnamed sheet. */
    List<Person> findBySheetId(String sheetId, Sort sort);
}
