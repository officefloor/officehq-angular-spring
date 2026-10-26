package net.officefloor.hq.app.rollup;

import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A steward's dashboard of the sheet: how many of its claims are PENDING, how many are in REVIEW, and how
 *  many are overdue and count. Only a steward (by the role assigned, or by a delegation covering the
 *  sheet's today) has one; anyone else's is empty. */
@Service
public class DashboardService {

    /** The dashboard's counts; all null for a user who is not a steward. */
    public record Dashboard(Integer pending, Integer review, Integer overdue) {
        static final Dashboard EMPTY = new Dashboard(null, null, null);
    }

    private final SettingService settings;
    private final ClaimRepository claims;
    private final Rollup rollup;

    public DashboardService(SettingService settings, ClaimRepository claims, Rollup rollup) {
        this.settings = settings;
        this.claims = claims;
        this.rollup = rollup;
    }

    /** The user's dashboard of the sheet, as the store holds it now. */
    @Transactional(readOnly = true)
    public Dashboard dashboardOf(String sheetId, String user) {
        if (user == null || user.isBlank() || settings.roleOf(sheetId, user) != Role.STEWARD) return Dashboard.EMPTY;
        int pending = 0;
        int review = 0;
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.PENDING) pending++;
            else if (claim.getStatus() == ClaimStatus.REVIEW) review++;
        }
        return new Dashboard(pending, review, rollup.overdueCount(sheetId));
    }
}
