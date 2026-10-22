package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stewards delegating the steward role to user names for a range of the sheet's today. */
@Service
public class DelegationService {

    private final DelegationRepository delegations;
    private final RoleRepository roles;
    private final SheetService sheets;
    private final Audit audit;

    public DelegationService(DelegationRepository delegations, RoleRepository roles, SheetService sheets, Audit audit) {
        this.delegations = delegations;
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The delegations made on the sheet, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> list(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * The steward a user name acts for on the sheet's today: the one who made the latest delegation to
     * it whose range holds the sheet's today; empty when none does.
     */
    @Transactional(readOnly = true)
    public Optional<String> actingFor(String sheetId, String userName) {
        List<Delegation> made = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, userName);
        if (made.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = sheets.today(sheetId);
        return made.stream()
                .filter(d -> d.covers(today)).map(Delegation::getSteward).findFirst();
    }

    /**
     * Delegate the steward role to a user name from a date to a date, inclusive. Refused unless the
     * user delegating is a steward in their own right, and when the to-date is before the from-date.
     */
    @Transactional
    public Delegation delegate(String sheetId, String user, String userName, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        boolean steward = roles.findBySheetIdAndUserName(sheetId, user)
                .map(r -> Role.STEWARD.equals(r.getRole())).orElse(false);
        if (!steward) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward delegates the steward role.");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, userName, user, from, to));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + userName
                + " from=" + from + " to=" + to);
        return saved;
    }
}
