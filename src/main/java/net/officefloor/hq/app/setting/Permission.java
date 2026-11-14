package net.officefloor.hq.app.setting;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** An action the sheet's permission matrix governs, with the roles its standing rule lets take it. */
public enum Permission {
    OPEN(EnumSet.of(Role.CLERK, Role.RESTRICTED, Role.STEWARD)),
    SETTLE(EnumSet.of(Role.CLERK, Role.RESTRICTED, Role.STEWARD)),
    VOID(EnumSet.of(Role.CLERK, Role.RESTRICTED, Role.STEWARD)),
    REASSIGN(EnumSet.of(Role.STEWARD)),
    CLOSE(EnumSet.of(Role.CLERK, Role.RESTRICTED, Role.STEWARD)),
    REOPEN(EnumSet.of(Role.STEWARD));

    private final Set<Role> standing;

    Permission(Set<Role> standing) {
        this.standing = standing;
    }

    /** The roles that may take the action when the matrix leaves it blank. */
    public Set<Role> standing() {
        return standing;
    }

    /** The name the screen, the audit and the store use for the action. */
    public String label() {
        return name().toLowerCase();
    }

    /** The action with this label; empty when there is none. */
    public static Optional<Permission> of(String label) {
        return Arrays.stream(values()).filter(p -> p.label().equals(label)).findFirst();
    }
}
