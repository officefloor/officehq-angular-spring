package net.officefloor.hq.app.claim;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimNumberSequenceRepository extends JpaRepository<ClaimNumberSequence, Long> {

    Optional<ClaimNumberSequence> findBySheetIdAndPrefix(String sheetId, String prefix);
}
