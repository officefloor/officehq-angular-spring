package net.officefloor.hq.app.setting;

/** The sheet's permission matrix does not let the user's role take the action. */
public class PermissionRefusedException extends RuntimeException {

    public PermissionRefusedException(String user, Permission action) {
        super(user + "'s role may not " + action.label() + " on this sheet");
    }
}
