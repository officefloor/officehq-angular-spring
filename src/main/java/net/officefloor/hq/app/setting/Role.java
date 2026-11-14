package net.officefloor.hq.app.setting;

import java.util.Arrays;
import java.util.Optional;

/** The role a sheet's settings give a user name; any name not given one is a clerk. A viewer sees
 *  everything and can change nothing. A restricted user acts as a clerk, but sees on the people list
 *  only the people of the crews they lead or belong to and themselves. */
public enum Role {
    CLERK, STEWARD, VIEWER, RESTRICTED;

    /** The name the screen and the audit use for the role. */
    public String label() {
        return name().toLowerCase();
    }

    /** The role with this label; empty when there is none. */
    public static Optional<Role> of(String label) {
        return Arrays.stream(values()).filter(role -> role.label().equals(label)).findFirst();
    }
}
