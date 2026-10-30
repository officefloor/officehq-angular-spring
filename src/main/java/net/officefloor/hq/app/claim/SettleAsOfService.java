package net.officefloor.hq.app.claim;

import java.time.LocalDate;
import net.officefloor.hq.app.asof.SheetPasts;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Settle a claim as of a date on or before the sheet's today: its frozen figure is its due as it stood at the
 * end of that day, as the as-of view of the sheet works it out (see {@link SheetPasts}). A date after the
 * sheet's today, or a claim the sheet did not have then, is refused and nothing changes; otherwise it is
 * settled as {@link ClaimService#move} settles it, with that due.
 */
@Service
public class SettleAsOfService {

    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetPasts pasts;

    public SettleAsOfService(ClaimService claims, SheetService sheets, SheetPasts pasts) {
        this.claims = claims;
        this.sheets = sheets;
        this.pasts = pasts;
    }

    /** Settle the claim as of the date; returns the person it belongs to. */
    public long settle(String sheetId, String user, long claimId, LocalDate on) {
        if (on.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim cannot be settled as of " + on + ", after the sheet's today");
        }
        ClaimService.DueThen then = pasts.asOf(sheetId, on, () -> claims.dueThen(sheetId, claimId, on))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The claim did not exist on " + on + ", so it cannot be settled as of then"));
        return claims.settleAsOf(sheetId, user, claimId, then);
    }
}
