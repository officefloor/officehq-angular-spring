package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the role each sheet gives a user name: clerk (any name with none), steward or viewer; and the
 *  delegations of the steward role, under which a user acts as a steward while the sheet's today is in range. */
@Service
public class RoleService {

    private final SheetRoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetRepository sheets;
    private final Audit audit;

    public RoleService(SheetRoleRepository roles, DelegationRepository delegations, SheetRepository sheets,
            Audit audit) {
        this.roles = roles;
        this.delegations = delegations;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SheetRole> list(String sheetId) {
        requireSheet(sheetId);
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole set(String sheetId, String user, String name, String role) {
        requireSheet(sheetId);
        SheetRole row = roles.findBySheetIdAndUserName(sheetId, name).orElse(null);
        if (row == null) {
            try {
                row = roles.saveAndFlush(new SheetRole(sheetId, name, role));
            } catch (DataIntegrityViolationException e) {
                throw new RoleConflictException();
            }
        } else {
            row.setRole(role);
            row = roles.saveAndFlush(row);
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role);
        return row;
    }

    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        requireSheet(sheetId);
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** A steward delegates the steward role to a user name from a date (none: the sheet's today) to a date
     *  (none: the from-date), inclusive. Anyone but a steward is refused, as is a to-date before the from-date. */
    @Transactional
    public Delegation delegate(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (!hasStewardRole(sheetId, user)) {
            throw new DelegationRefusedException("Only a steward may delegate the steward role.");
        }
        LocalDate start = from == null ? sheet.getToday() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw new DelegationRefusedException("The to-date may not be before the from-date.");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, user, name, start, end));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + name
                + " from=" + start + " to=" + end);
        return saved;
    }

    /** Whether the sheet makes the user name a steward: given the role, or delegated it for the sheet's today. */
    @Transactional(readOnly = true)
    public boolean isSteward(String sheetId, String name) {
        return hasStewardRole(sheetId, name) || delegatorOf(sheetId, name) != null;
    }

    /** The steward the user acts for: the one who delegated them the steward role for the sheet's today, when
     *  they are not a steward themselves; none otherwise. */
    @Transactional(readOnly = true)
    public String actingFor(String sheetId, String name) {
        return hasStewardRole(sheetId, name) ? null : delegatorOf(sheetId, name);
    }

    private boolean hasStewardRole(String sheetId, String name) {
        return roles.findBySheetIdAndUserName(sheetId, name)
                .map(r -> SheetRole.STEWARD.equals(r.getRole()))
                .orElse(false);
    }

    /** The latest delegator of the steward role to the user covering the sheet's today; none when none does. */
    private String delegatorOf(String sheetId, String name) {
        List<Delegation> given = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, name);
        if (given.isEmpty()) {
            return null;
        }
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        if (today == null) {
            return null;
        }
        return given.stream().filter(d -> d.covers(today)).map(Delegation::getDelegator).findFirst().orElse(null);
    }

    /** Whether the sheet makes the user name a viewer, who sees the sheet and changes nothing; a user delegated
     *  the steward role acts as a steward. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String name) {
        if (delegatorOf(sheetId, name) != null) {
            return false;
        }
        return roles.findBySheetIdAndUserName(sheetId, name)
                .map(r -> SheetRole.VIEWER.equals(r.getRole()))
                .orElse(false);
    }

    /** Refuse any change by a viewer. */
    public void requireNotViewer(String sheetId, String name) {
        if (isViewer(sheetId, name)) {
            throw new ActionNotAllowedException("change anything");
        }
    }

    /** Whether the user's role on the sheet may take an action, by the sheet's permission matrix (an action
     *  with no roles set keeps the standing rule). */
    @Transactional(readOnly = true)
    public boolean allows(String sheetId, String name, String action) {
        if (isViewer(sheetId, name)) {
            return false;
        }
        String role = isSteward(sheetId, name) ? SheetRole.STEWARD : SheetRole.CLERK;
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).allows(action, role);
    }

    /** Refuse an action the user's role on the sheet may not take. */
    public void require(String sheetId, String name, String action) {
        if (!allows(sheetId, name, action)) {
            throw new ActionNotAllowedException(action);
        }
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
