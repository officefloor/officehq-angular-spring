package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settles part of an OPEN claim: splits the part off and settles it at once. */
@Service
public class PartSettleService {

    private final ClaimService service;
    private final RollupService rollups;

    public PartSettleService(ClaimService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
    }

    /** Split an amount off an OPEN claim exactly as a split does, then settle the new claim for the
     *  user exactly as settling it does (PENDING when the clerk limit says so), all or nothing. The
     *  four-eyes rules and the proof rule are judged on the original claim, not on the new one the
     *  user opened by splitting it. A split or settle that would be refused refuses the whole of it. */
    @Transactional
    public ClaimService.Split settlePart(String sheetId, String user, Long personId, Long id, BigDecimal amount) {
        service.refusePartSettle(sheetId, user, personId, id);
        ClaimService.Split split = rollups.track(sheetId, user,
                () -> service.split(sheetId, user, personId, id, amount));
        Claim settled = rollups.track(sheetId, user,
                () -> service.settleSplitOff(sheetId, user, personId, split.added().getId()));
        return new ClaimService.Split(split.kept(), settled);
    }
}
