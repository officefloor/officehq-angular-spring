package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonStatementRepository extends JpaRepository<PersonStatement, Long> {

    /** The sheet's statements, oldest first. */
    List<PersonStatement> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's statements, oldest first. */
    List<PersonStatement> findByPersonIdOrderByIdAsc(Long personId);
}
