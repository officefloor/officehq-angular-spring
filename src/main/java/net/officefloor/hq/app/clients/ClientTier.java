package net.officefloor.hq.app.clients;

/**
 * A client's tier: a tier the workspace gives its own approval limit (in US dollars) uses it instead of the
 * workspace's approval limit.
 */
public enum ClientTier {
    STANDARD, KEY, NEW;

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
