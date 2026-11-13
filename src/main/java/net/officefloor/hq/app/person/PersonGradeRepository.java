package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonGradeRepository extends JpaRepository<PersonGrade, Long> {

    /** The sheet's people's grades. */
    List<PersonGrade> findBySheetId(String sheetId);

    /** A person's grades. */
    List<PersonGrade> findByPersonId(Long personId);

    Optional<PersonGrade> findByPersonIdAndFrom(Long personId, LocalDate from);

    Optional<PersonGrade> findByPersonIdAndFromIsNull(Long personId);
}
