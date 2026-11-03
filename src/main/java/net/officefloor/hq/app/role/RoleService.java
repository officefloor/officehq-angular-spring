package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The roles user names have on a sheet: clerk (the default for any name), steward, restricted (who
 *  lists only the people of their crews and their own person) or viewer
 *  (who may change nothing); and the steward role a steward delegates to a user name for a range of
 *  days, inside which, by the sheet's today, the user acts as a steward. */
@Service
public class RoleService {

    private final SheetRoleRepository roles;
    private final RoleRevisionRepository revisions;
    private final DelegationRepository delegations;
    private final SheetService sheets;
    private final Audit audit;

    public RoleService(SheetRoleRepository roles, RoleRevisionRepository revisions, DelegationRepository delegations,
            SheetService sheets, Audit audit) {
        this.roles = roles;
        this.revisions = revisions;
        this.delegations = delegations;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The roles given on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The roles in force on the sheet at the end of a day, by user name: the last role given to each
     *  name on or before the day; a name given none by then is not listed. */
    @Transactional(readOnly = true)
    public List<SheetRole> listOn(String sheetId, LocalDate day) {
        Map<String, SheetRole> then = new TreeMap<>();
        for (RoleRevision r : revisions.findBySheetIdAndRecordedOnLessThanEqualOrderByRecordedOnAscIdAsc(sheetId, day)) {
            then.put(r.getUserName(), new SheetRole(sheetId, r.getUserName(), r.getRole()));
        }
        return List.copyOf(then.values());
    }

    /** The role a user name acts in on the sheet: steward while a delegation to it holds the sheet's
     *  today, else the role it was given. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String userName) {
        return delegatorOf(sheetId, userName).isPresent() ? SheetRole.STEWARD : givenRoleOf(sheetId, userName);
    }

    /** The role a user name acted in on the sheet at the end of a day: steward while a delegation to
     *  it held the day from a user who had been given the steward role by then, else the last role
     *  given to it on or before the day (clerk when none was). */
    @Transactional(readOnly = true)
    public String roleOn(String sheetId, String userName, LocalDate day) {
        if (delegations.findFirstBySheetIdAndUserNameAndFromDateLessThanEqualAndToDateGreaterThanEqualOrderByIdDesc(
                sheetId, userName, day, day)
                .filter(d -> SheetRole.STEWARD.equals(givenRoleOn(sheetId, d.getDelegatedBy(), day))).isPresent()) {
            return SheetRole.STEWARD;
        }
        return givenRoleOn(sheetId, userName, day);
    }

    /** The last role given to a user name on the sheet on or before a day (clerk when none was). */
    private String givenRoleOn(String sheetId, String userName, LocalDate day) {
        return revisions
                .findFirstBySheetIdAndUserNameAndRecordedOnLessThanEqualOrderByRecordedOnDescIdDesc(sheetId, userName, day)
                .map(RoleRevision::getRole)
                .orElse(SheetRole.CLERK);
    }

    /** The role given to a user name on the sheet: clerk unless it was given another. */
    @Transactional(readOnly = true)
    public String givenRoleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(SheetRole::getRole).orElse(SheetRole.CLERK);
    }

    /** The steward a user name acts for on the sheet: the one who delegated the steward role to it
     *  for a range holding the sheet's today (the latest such delegation). */
    @Transactional(readOnly = true)
    public Optional<String> delegatorOf(String sheetId, String userName) {
        LocalDate today = sheets.today(sheetId);
        return delegations
                .findFirstBySheetIdAndUserNameAndFromDateLessThanEqualAndToDateGreaterThanEqualOrderByIdDesc(
                        sheetId, userName, today, today)
                .map(Delegation::getDelegatedBy);
    }

    /** The delegations made on the sheet, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to a user name from a day (the sheet's today when none) to a day (the
     *  from-day when none), inclusive. Refused unless the user was given the steward role, or when the
     *  range ends before it starts. */
    @Transactional
    public synchronized Delegation delegate(String sheetId, String user, String userName, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        if (!SheetRole.STEWARD.equals(givenRoleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may delegate the steward role");
        }
        LocalDate start = from == null ? sheets.today(sheetId) : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A delegation cannot end before it starts");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, userName, user, start, end));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + userName
                + " from=" + start + " to=" + end);
        return saved;
    }

    /** Whether a user name's role on the sheet may take an action of the permission matrix (open,
     *  settle, void, reassign, close, reopen, today). */
    @Transactional(readOnly = true)
    public boolean may(String sheetId, String userName, String action) {
        return sheets.rolesAllowed(sheetId, action).contains(roleOf(sheetId, userName));
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public synchronized SheetRole set(String sheetId, String user, String userName, String role) {
        sheets.load(sheetId, user);
        SheetRole given = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new SheetRole(sheetId, userName, role));
        given.setRole(role);
        SheetRole saved = roles.saveAndFlush(given);
        revisions.saveAndFlush(new RoleRevision(sheetId, userName, role, sheets.today(sheetId)));
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + userName + " role=" + role);
        return saved;
    }
}
