package net.officefloor.hq.app.rollup;

import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A steward's dashboard over the sheet and all its descendants (its children, the sheets naming it as
 *  their parent, then theirs, and so on): how many of their claims are PENDING, how many are in REVIEW, and
 *  how many are overdue and count, each sheet's overdue as that sheet shows it. Only a steward (by the role assigned, or by a delegation covering the
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
    private final ConsolidatedService consolidated;

    public DashboardService(SettingService settings, ClaimRepository claims, Rollup rollup,
            ConsolidatedService consolidated) {
        this.settings = settings;
        this.consolidated = consolidated;
        this.claims = claims;
        this.rollup = rollup;
    }

    /** The user's dashboard over the sheet's tree, as the store holds it now, or with the date, as every
     *  sheet of the tree stood at the end of that day (only within the as-of view's rolled back transaction). */
    @Transactional(readOnly = true)
    public Dashboard dashboardOf(String sheetId, String user, LocalDate asOf) {
        if (user == null || user.isBlank() || settings.roleOf(sheetId, user) != Role.STEWARD) return Dashboard.EMPTY;
        List<String> tree = new ArrayList<>(List.of(sheetId));
        tree.addAll(consolidated.descendantSheets(sheetId, asOf));
        int pending = 0;
        int review = 0;
        int overdue = 0;
        for (String sheet : tree) {
            for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheet)) {
                if (claim.getStatus() == ClaimStatus.PENDING) pending++;
                else if (claim.getStatus() == ClaimStatus.REVIEW) review++;
            }
            overdue += rollup.overdueCount(sheet);
        }
        return new Dashboard(pending, review, overdue);
    }
}
