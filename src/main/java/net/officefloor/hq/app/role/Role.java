package net.officefloor.hq.app.role;

import java.util.Arrays;
import java.util.Optional;

/** The role a user name has on a sheet: clerk (any name not given another role) or steward. */
public enum Role {
    CLERK, STEWARD;

    /** The role's name as typed and shown, e.g. "steward". */
    public String label() {
        return name().toLowerCase();
    }

    public static Optional<Role> of(String label) {
        return Arrays.stream(values()).filter(role -> role.label().equals(label)).findFirst();
    }
}
