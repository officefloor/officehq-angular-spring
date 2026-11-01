package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
