package net.officefloor.hq.app.role;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The roles user names have on a sheet: listed, set and looked up. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final RoleChangeRepository changes;
    private final SheetService sheets;
    private final DelegationService delegations;
    private final Audit audit;

    public RoleService(RoleRepository roles, RoleChangeRepository changes, SheetService sheets,
            DelegationService delegations, Audit audit) {
        this.roles = roles;
        this.changes = changes;
        this.sheets = sheets;
        this.delegations = delegations;
        this.audit = audit;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<Role> list(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /**
     * The roles in force on the sheet at the end of a date, by user name: for each name, the last role
     * set on it on a sheet-today on or before that date. A name with none set by then is not listed.
     */
    @Transactional(readOnly = true)
    public List<Role> listAsOf(String sheetId, LocalDate asOf) {
        Map<String, Role> inForce = new LinkedHashMap<>();
        for (RoleChange change : changes.findBySheetIdOrderByIdAsc(sheetId)) {
            if (change.getSetOn() == null || !change.getSetOn().isAfter(asOf)) {
                inForce.put(change.getUserName(), new Role(sheetId, change.getUserName(), change.getRole()));
            }
        }
        return inForce.values().stream().sorted(Comparator.comparing(Role::getUserName)).toList();
    }

    /** The role set for a user name on the sheet in force at the end of a date (see {@link #listAsOf}), else clerk. */
    @Transactional(readOnly = true)
    public String roleAsOf(String sheetId, String userName, LocalDate asOf) {
        return listAsOf(sheetId, asOf).stream().filter(role -> role.getUserName().equals(userName))
                .map(Role::getRole).findFirst().orElse(Role.CLERK);
    }

    /**
     * The role a user name acts in on the sheet: steward while a delegation to it is in force on the
     * sheet's today, else the one set for it, else clerk.
     */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String userName) {
        if (delegations.actingFor(sheetId, userName).isPresent()) {
            return Role.STEWARD;
        }
        return roles.findBySheetIdAndUserName(sheetId, userName).map(Role::getRole).orElse(Role.CLERK);
    }

    /**
     * Whether a user name may take an action (open, settle, void, reassign, close, reopen or today) on the
     * sheet: its role is among those the sheet's permission matrix sets for it, else its standing rule.
     */
    @Transactional(readOnly = true)
    public boolean permits(String sheetId, String userName, String action) {
        return sheets.load(sheetId).permits(action, roleOf(sheetId, userName));
    }

    /** Set the role of a user name on the sheet, replacing any it had. */
    @Transactional
    public Role set(String sheetId, String user, String userName, String role) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Role saved = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new Role(sheetId, userName, role));
        saved.setRole(role);
        saved = roles.saveAndFlush(saved);
        changes.save(new RoleChange(sheetId, saved.getUserName(), saved.getRole(), today));
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user
                + " user=" + saved.getUserName() + " role=" + saved.getRole());
        return saved;
    }
}
