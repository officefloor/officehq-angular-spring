package net.officefloor.hq.app.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * An action the sheet's permission matrix governs, with the roles that may take it when the matrix leaves
 * it blank (the standing rule): open, settle, void and close by clerk and steward; reassign and reopen by
 * steward. Its setting key is perm-&lt;action&gt;, its value a comma separated list of roles.
 */
public enum Permission {
    OPEN(EnumSet.of(Role.CLERK, Role.STEWARD)),
    SETTLE(EnumSet.of(Role.CLERK, Role.STEWARD)),
    VOID(EnumSet.of(Role.CLERK, Role.STEWARD)),
    REASSIGN(EnumSet.of(Role.STEWARD)),
    CLOSE(EnumSet.of(Role.CLERK, Role.STEWARD)),
    REOPEN(EnumSet.of(Role.STEWARD));

    /** A permission value as written: blank, or roles separated by commas. */
    public static final String ROLES = "(\\s*(clerk|steward)\\s*(,\\s*(clerk|steward)\\s*)*)?";

    private final Set<Role> standing;

    Permission(Set<Role> standing) {
        this.standing = standing;
    }

    /** The action as it is written: lower case. */
    public String action() {
        return name().toLowerCase();
    }

    /** The setting key: perm-&lt;action&gt;. */
    public String key() {
        return "perm-" + action();
    }

    /** Whether the role may take the action under the value set (null or blank: the standing rule). */
    public boolean allows(String value, Role role) {
        if (value == null || value.isBlank()) {
            return standing.contains(role);
        }
        for (String r : value.split(",")) {
            if (r.trim().equals(role.key())) {
                return true;
            }
        }
        return false;
    }
}
