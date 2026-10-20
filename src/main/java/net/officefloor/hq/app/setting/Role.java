package net.officefloor.hq.app.setting;

import java.util.Arrays;
import java.util.Optional;

/** The role a user name has on a sheet; any name without one is a clerk. A viewer sees the sheet and changes
 * nothing. */
public enum Role {
    CLERK, STEWARD, VIEWER;

    /** The role as it is typed and shown, e.g. "steward". */
    public String code() {
        return name().toLowerCase();
    }

    public static Optional<Role> of(String code) {
        return Arrays.stream(values()).filter(r -> r.code().equals(code)).findFirst();
    }
}
