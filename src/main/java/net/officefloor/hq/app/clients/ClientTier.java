package net.officefloor.hq.app.clients;

/**
 * A client's tier: a tier the workspace gives its own approval limit (in US dollars) uses it instead of the
 * workspace's approval limit. An approval given for a client's invoice lasts as many days as its tier allows.
 */
public enum ClientTier {
    STANDARD(30), KEY(90), NEW(7);

    private final int approvalDays;

    ClientTier(int approvalDays) {
        this.approvalDays = approvalDays;
    }

    /** The number of days an approval stays good after the day it was given. */
    public int approvalDays() {
        return approvalDays;
    }

    /** The tier of the given name; STANDARD when null or blank, null when no tier has it. */
    public static ClientTier orStandard(String name) {
        if (name == null || name.isBlank()) {
            return STANDARD;
        }
        for (ClientTier tier : values()) {
            if (tier.name().equals(name.trim())) {
                return tier;
            }
        }
        return null;
    }
}
