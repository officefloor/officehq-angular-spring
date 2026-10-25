package net.officefloor.hq.app.settings;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** An action the sheet's permission matrix decides who may take, with the roles that may take it when
 * the matrix leaves it blank. */
public enum Action {
    OPEN(EnumSet.of(Role.CLERK, Role.STEWARD, Role.RESTRICTED)),
    SETTLE(EnumSet.of(Role.CLERK, Role.STEWARD, Role.RESTRICTED)),
    VOID(EnumSet.of(Role.CLERK, Role.STEWARD, Role.RESTRICTED)),
    REASSIGN(EnumSet.of(Role.STEWARD)),
    CLOSE(EnumSet.of(Role.CLERK, Role.STEWARD, Role.RESTRICTED)),
    REOPEN(EnumSet.of(Role.STEWARD)),
    TODAY(EnumSet.of(Role.CLERK, Role.STEWARD, Role.RESTRICTED));

    private final Set<Role> standing;

    Action(Set<Role> standing) {
        this.standing = standing;
    }

    /** The roles that may take the action when the matrix leaves it blank. */
    public Set<Role> standing() {
        return standing;
    }

    /** The action's name as the settings show it: open, settle, and so on. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
