package net.officefloor.hq.app.dashboard;

import java.util.Optional;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A steward's dashboard: the sheet's claims waiting on a steward, and its overdue claims. */
@Service
public class DashboardService {

    /** How many of the sheet's claims are PENDING, in REVIEW, and overdue among those that count. */
    public record Dashboard(long pendingCount, long reviewCount, long overdueCount) {
    }

    private final ClaimRepository claims;
    private final RoleService roles;
    private final Rollups rollups;

    public DashboardService(ClaimRepository claims, RoleService roles, Rollups rollups) {
        this.claims = claims;
        this.roles = roles;
        this.rollups = rollups;
    }

    /** The sheet's dashboard as it stands now, for a user acting as steward on it; empty for anyone else. */
    @Transactional(readOnly = true)
    public Optional<Dashboard> of(String sheetId, String userName) {
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, userName))) {
            return Optional.empty();
        }
        return Optional.of(new Dashboard(claims.countBySheetIdAndStatus(sheetId, ClaimStatus.PENDING),
                claims.countBySheetIdAndStatus(sheetId, ClaimStatus.REVIEW), rollups.take(sheetId).overdueCount()));
    }
}
