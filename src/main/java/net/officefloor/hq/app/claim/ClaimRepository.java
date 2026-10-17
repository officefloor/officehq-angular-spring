package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    Optional<Claim> findFirstBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);
}
