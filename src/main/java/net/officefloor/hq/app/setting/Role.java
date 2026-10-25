package net.officefloor.hq.app.setting;

import java.util.Arrays;
import java.util.Optional;

/** The role a sheet gives a user name: a clerk (the default for any name) or a steward. */
public enum Role {
    CLERK, STEWARD;

    /** The role's name as it is typed, shown and stored. */
    public String label() {
        return name().toLowerCase();
    }

    /** The role with this typed name, if there is one. */
    public static Optional<Role> of(String label) {
        return Arrays.stream(values()).filter((r) -> r.label().equals(label)).findFirst();
    }
}
