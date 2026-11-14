package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRetirementRepository extends JpaRepository<PersonRetirement, Long> {

    /** The sheet's past retirements in the order they ended. */
    List<PersonRetirement> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's past retirements in the order they ended. */
    List<PersonRetirement> findByPersonIdOrderByIdAsc(Long personId);
}
