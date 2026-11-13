package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonHoldRepository extends JpaRepository<PersonHold, Long> {

    /** The sheet's holds in the order they were put on. */
    List<PersonHold> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's holds in the order they were put on. */
    List<PersonHold> findByPersonIdOrderByIdAsc(Long personId);
}
