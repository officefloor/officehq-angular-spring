package net.officefloor.hq.app.settings;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The steward role delegated on a sheet: a steward gives it to a user from a from-date to a to-date,
 * inclusive; while the sheet's today is inside the range the user acts as a steward in every rule, and
 * acts for the steward who delegated it (see {@link Audit#change}). */
@Service
public class DelegationService {

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final SheetDelegationRepository delegations;
    private final Audit audit;

    public DelegationService(SheetRepository sheets, SheetRoleRepository roles, SheetDelegationRepository delegations,
            Audit audit) {
        this.sheets = sheets;
        this.roles = roles;
        this.delegations = delegations;
        this.audit = audit;
    }

    /** The delegations made on the sheet, oldest first. */
    @Transactional(readOnly = true)
    public List<SheetDelegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to {@code user} from {@code from} (the sheet's today when null) to
     * {@code to}, inclusive. Refused unless {@code by} is a steward of the sheet by the role given (not by
     * a delegation), and when the to-date is before the from-date. */
    @Transactional
    public SheetDelegation delegate(String sheetId, String by, String user, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (givenRole(sheetId, by) != Role.STEWARD) throw new DelegationRefusedException("Only a steward may delegate.");
        LocalDate on = from == null ? sheet.getToday() : from;
        if (to.isBefore(on)) throw new DelegationRefusedException("The to-date is before the from-date.");
        SheetDelegation made = delegations.saveAndFlush(new SheetDelegation(sheetId, user, by, on, to));
        audit.change("DELEGATED", sheetId, by, "user=" + user, "from=" + on, "to=" + to);
        return made;
    }

    /** The steward the user acts for on the sheet's today: who delegated the steward role to them in a
     * range the today is inside (the earliest such delegation); empty when none does, or when the user
     * is a steward by the role given. */
    @Transactional(readOnly = true)
    public Optional<String> actingFor(String sheetId, String user) {
        if (user == null || givenRole(sheetId, user) == Role.STEWARD) return Optional.empty();
        List<SheetDelegation> made = delegations.findBySheetIdAndUserOrderByIdAsc(sheetId, user);
        if (made.isEmpty()) return Optional.empty();
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        return made.stream().filter(d -> d.covers(today)).map(SheetDelegation::getDelegator).findFirst();
    }

    /** The role given to the user on the sheet: a clerk unless made something else. */
    Role givenRole(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }
}
