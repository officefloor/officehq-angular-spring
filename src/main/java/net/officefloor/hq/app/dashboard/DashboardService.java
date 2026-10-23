package net.officefloor.hq.app.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward's dashboard: the claims waiting on a steward, and the overdue claims, over the sheet and all its
 * descendants.
 */
@Service
public class DashboardService {

    /** How many of the tree's claims are PENDING, in REVIEW, and overdue among those that count. */
    public record Dashboard(long pendingCount, long reviewCount, long overdueCount) {
    }

    private final ClaimRepository claims;
    private final RoleService roles;
    private final Rollups rollups;
    private final SheetRepository sheets;

    public DashboardService(ClaimRepository claims, RoleService roles, Rollups rollups, SheetRepository sheets) {
        this.claims = claims;
        this.roles = roles;
        this.rollups = rollups;
        this.sheets = sheets;
    }

    /**
     * The dashboard over the sheet and all its descendants, each as it stands now, for a user acting as
     * steward on the sheet; empty for anyone else.
     */
    @Transactional(readOnly = true)
    public Optional<Dashboard> of(String sheetId, String userName) {
        return of(sheetId, userName, null);
    }

    /** As {@link #of(String, String)}, each sheet's overdue claims read as if its today were a preview day (null: its own). */
    @Transactional(readOnly = true)
    public Optional<Dashboard> of(String sheetId, String userName, LocalDate preview) {
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, userName))) {
            return Optional.empty();
        }
        List<String> tree = new ArrayList<>();
        tree.add(sheetId);
        tree.addAll(sheets.descendants(sheetId));
        long pending = 0;
        long review = 0;
        long overdue = 0;
        for (String id : tree) {
            pending += claims.countBySheetIdAndStatus(id, ClaimStatus.PENDING);
            review += claims.countBySheetIdAndStatus(id, ClaimStatus.REVIEW);
            overdue += rollups.take(id, preview).overdueCount();
        }
        return Optional.of(new Dashboard(pending, review, overdue));
    }
}
