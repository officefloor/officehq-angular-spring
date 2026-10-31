package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The steward role's delegations on a sheet. */
@Service
public class DelegationService {

    private final DelegationRepository delegations;
    private final RoleService roles;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public DelegationService(DelegationRepository delegations, RoleService roles, SheetService sheets,
            SheetRepository sheetStore, Audit audit) {
        this.delegations = delegations;
        this.roles = roles;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's delegations, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> list(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to a user name from a from-date to a to-date, inclusive (each defaulting to the
     *  sheet's today). Only a steward assigned the role may delegate it: one acting as a steward by delegation may not.
     *  A to-date before the from-date is refused. */
    @Transactional
    public Delegation delegate(String sheetId, String user, String userName, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        if (!Role.STEWARD.equals(roles.assignedRoleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can delegate the steward role");
        }
        LocalDate fromDate = from != null ? from : today;
        LocalDate toDate = to != null ? to : today;
        if (toDate.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, userName, user, fromDate, toDate));
        audit.recordChangeOnCommit(sheetId, user, "DELEGATED sheet=" + sheetId + " by=" + user
                + " user=" + saved.getUserName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }
}
