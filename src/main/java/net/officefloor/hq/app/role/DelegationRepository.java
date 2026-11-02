package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findBySheetIdOrderByIdAsc(String sheetId);

    /** The latest delegation to a user name whose range holds the day. */
    Optional<Delegation> findFirstBySheetIdAndUserNameAndFromDateLessThanEqualAndToDateGreaterThanEqualOrderByIdDesc(
            String sheetId, String userName, LocalDate from, LocalDate to);
}
