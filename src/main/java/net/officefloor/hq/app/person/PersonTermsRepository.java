package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonTermsRepository extends JpaRepository<PersonTerms, Long> {

    /** The sheet's people's dated terms. */
    List<PersonTerms> findBySheetId(String sheetId);

    /** A person's dated terms. */
    List<PersonTerms> findByPersonId(Long personId);

    Optional<PersonTerms> findByPersonIdAndFrom(Long personId, LocalDate from);
}
