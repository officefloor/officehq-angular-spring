package net.officefloor.hq.app.setting;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** An action the sheet's permission matrix governs, with the roles that may take it under the standing rule (when
 * the sheet gives none of its own). */
public enum Permission {
    OPEN("open a claim", Role.CLERK, Role.STEWARD),
    SETTLE("settle a claim", Role.CLERK, Role.STEWARD),
    VOID("void a claim", Role.CLERK, Role.STEWARD),
    REASSIGN("move a claim", Role.STEWARD),
    CLOSE("close a period", Role.CLERK, Role.STEWARD),
    REOPEN("reopen a period", Role.STEWARD);

    private final String doing;
    private final List<Role> standing;

    Permission(String doing, Role... standing) {
        this.doing = doing;
        this.standing = List.of(standing);
    }

    /** The action as it is named, e.g. "open". */
    public String code() {
        return name().toLowerCase();
    }

    /** The key the setting is kept and audited by, e.g. "perm-open". */
    public String key() {
        return "perm-" + code();
    }

    /** What the action does, e.g. "open a claim". */
    public String doing() {
        return doing;
    }

    /** The roles that may take the action when the sheet gives none of its own. */
    public List<Role> standing() {
        return standing;
    }

    public static Optional<Permission> of(String code) {
        return Arrays.stream(values()).filter(p -> p.code().equals(code)).findFirst();
    }
}
