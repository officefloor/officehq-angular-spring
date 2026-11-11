package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the delegations of the steward role on a sheet. While the sheet's today is inside a delegation's range, its
 * user acts as a steward, on behalf of the steward who delegated.
 */
@Service
public class DelegationService {

    private final DelegationRepository delegations;
    private final RoleRepository roles;
    private final SheetRepository sheets;
    private final Audit audit;

    public DelegationService(DelegationRepository delegations, RoleRepository roles, SheetRepository sheets,
            Audit audit) {
        this.delegations = delegations;
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<Delegation> list(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Whether the user holds the steward role itself, not by delegation. */
    private boolean isSteward(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK) == Role.STEWARD;
    }

    /**
     * The steward on whose behalf the user acts as a steward on the sheet's today: the one who delegated last of the
     * delegations covering it; empty when none covers it, or when the user is a steward in their own right.
     */
    @Transactional(readOnly = true)
    public Optional<String> delegatorOf(String sheetId, String user) {
        Optional<LocalDate> today = sheets.findById(sheetId).map(Sheet::getToday);
        if (today.isEmpty() || isSteward(sheetId, user)) {
            return Optional.empty();
        }
        return delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user).stream()
                .filter(d -> d.covers(today.get())).map(Delegation::getDelegatedBy).findFirst();
    }

    /** A steward delegates the steward role to the user from the from-date (blank: the sheet's today) to the to-date. */
    @Transactional
    public Delegation delegate(String sheetId, String by, String user, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
        if (!isSteward(sheetId, by)) {
            throw new DelegationForbiddenException(sheetId, by);
        }
        LocalDate fromDate = from == null ? sheet.getToday() : from;
        if (to.isBefore(fromDate)) {
            throw new DelegationDatesException(fromDate, to);
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, user, by, fromDate, to));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + by + " user=" + user + " from="
                + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }
}
