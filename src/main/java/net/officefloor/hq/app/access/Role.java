package net.officefloor.hq.app.access;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a user may do on a sheet: a clerk settles only within the clerk limit (above it the claim goes
 * PENDING), a steward settles anything and approves or rejects a PENDING claim; a viewer sees everything
 * and can change nothing; a restricted user acts as a clerk but has listed only the people of the crews
 * they lead or belong to and that person themselves.
 */
public enum Role {
    CLERK, STEWARD, VIEWER, RESTRICTED;

    /** The role as it is written: lower case. */
    public String key() {
        return name().toLowerCase();
    }

    public static Optional<Role> of(String key) {
        return Arrays.stream(values()).filter(r -> r.key().equals(key)).findFirst();
    }
}
