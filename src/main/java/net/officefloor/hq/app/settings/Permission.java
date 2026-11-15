package net.officefloor.hq.app.settings;

import java.util.Set;

/** An action whose roles the sheet's permission matrix sets, with the roles that may take it when
 *  the matrix leaves it blank. */
public enum Permission {
    OPEN(Set.of(Role.CLERK, Role.STEWARD)),
    SETTLE(Set.of(Role.CLERK, Role.STEWARD)),
    VOID(Set.of(Role.CLERK, Role.STEWARD)),
    REASSIGN(Set.of(Role.STEWARD)),
    CLOSE(Set.of(Role.CLERK, Role.STEWARD)),
    REOPEN(Set.of(Role.STEWARD)),
    TODAY(Set.of(Role.CLERK, Role.STEWARD));

    private final Set<Role> standing;

    Permission(Set<Role> standing) {
        this.standing = standing;
    }

    /** The roles that may take the action when the matrix leaves it blank. */
    public Set<Role> standing() {
        return standing;
    }

    /** The action as it is stored, and as its setting's key follows {@code perm-}. */
    public String key() {
        return name().toLowerCase();
    }

    public static Permission of(String key) {
        return valueOf(key.toUpperCase());
    }
}
