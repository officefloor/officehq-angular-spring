package net.officefloor.hq.app.claim;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimSequenceRepository extends JpaRepository<ClaimSequence, Long> {

    Optional<ClaimSequence> findBySheetIdAndPrefix(String sheetId, String prefix);
}
