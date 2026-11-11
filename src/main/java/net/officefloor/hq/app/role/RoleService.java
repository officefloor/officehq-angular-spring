package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The roles a sheet assigns to user names: clerk (the default for any name), steward, viewer or
 * restricted (a clerk who sees only their crews' people on the people list). A
 * steward can delegate the steward role to a user for a from-date to a to-date, inclusive: while
 * the sheet's today is inside it the user acts as a steward on the steward's behalf.
 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetService sheets;
    private final Audit audit;

    public RoleService(RoleRepository roles, DelegationRepository delegations, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.delegations = delegations;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Role> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless assigned otherwise; steward while a delegation to them is in force. */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String user) {
        String assigned = assignedRoleOf(sheetId, user);
        return delegatorOf(sheetId, user).isPresent() ? Role.STEWARD : assigned;
    }

    /** The role the sheet assigns the user, leaving aside any delegation. */
    private String assignedRoleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(Role::getRole).orElse(Role.CLERK);
    }

    /**
     * The steward the user acts for on the sheet's today: who made the latest delegation to them in
     * force then; none for a user who is a steward in their own right.
     */
    @Transactional(readOnly = true)
    public Optional<String> delegatorOf(String sheetId, String user) {
        if (Role.STEWARD.equals(assignedRoleOf(sheetId, user))) {
            return Optional.empty();
        }
        List<Delegation> theirs = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user);
        if (theirs.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = sheets.today(sheetId);
        return theirs.stream().filter(d -> d.covers(today)).findFirst().map(Delegation::getSteward);
    }

    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Delegate the steward role to a user from a from-date (none: the sheet's today) to a to-date,
     * inclusive. Refused (403) to anyone but a steward in their own right, and (400) for a to-date
     * before the from-date.
     */
    @Transactional
    public Delegation delegate(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.refuseIfArchived(sheetId);
        sheets.load(sheetId, user);
        if (!Role.STEWARD.equals(assignedRoleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can delegate the steward role.");
        }
        LocalDate fromDate = from != null ? from : sheets.today(sheetId);
        if (to.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, name, fromDate, to, user));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + name + " from=" + fromDate
                + " to=" + to);
        return saved;
    }

    /**
     * Refuse (403) an action the user's role may not take by the sheet's permission matrix (see
     * {@link net.officefloor.hq.app.sheet.Sheet#permits}).
     */
    // a refusal leaves the caller's transaction usable (a bulk action refuses one claim and goes on)
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public void refuseUnlessPermitted(String sheetId, String user, String action) {
        sheets.refuseIfArchived(sheetId);
        if (!sheets.sheet(sheetId).permits(action, roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not "
                    + ("today".equals(action) ? "change the sheet's today" : action) + " here.");
        }
    }

    /**
     * Refuse (403) any change by a viewer: a viewer sees the sheet and may change nothing; and
     * (423) any change at all to an archived sheet.
     */
    // a refusal leaves the caller's transaction usable (a bulk action refuses one claim and goes on)
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public void refuseViewer(String sheetId, String user) {
        sheets.refuseIfArchived(sheetId);
        if (Role.VIEWER.equals(roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may not change anything here.");
        }
    }

    /** Refuse (403) anyone but a steward (in their own right or by a delegation in force). */
    @Transactional(readOnly = true)
    public void refuseUnlessSteward(String sheetId, String user) {
        if (!Role.STEWARD.equals(roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can do that.");
        }
    }

    @Transactional
    public Role set(String sheetId, String user, String name, String role) {
        refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        Role assigned = roles.findBySheetIdAndUserName(sheetId, name).orElseGet(() -> new Role(sheetId, name, role));
        assigned.setRole(role);
        Role saved;
        try {
            saved = roles.saveAndFlush(assigned);
        } catch (DataIntegrityViolationException e) {
            // another user assigned this name a role at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This role was just set by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role);
        return saved;
    }
}
