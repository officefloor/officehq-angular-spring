package net.officefloor.hq.app.setting;

/** A parent sheet that is the sheet itself, has the sheet as an ancestor, or was never loaded. */
public class ParentRefusedException extends RuntimeException {

    public ParentRefusedException(String message) {
        super(message);
    }
}
