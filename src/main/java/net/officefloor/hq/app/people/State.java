package net.officefloor.hq.app.people;

/**
 * A person's workflow state; a person moves forward from DRAFT to REVIEW to FINAL, and back a step from REVIEW to DRAFT. FINAL is
 * terminal: once there a person cannot move forward or back.
 */
public enum State {
    DRAFT, REVIEW, FINAL;

    /** The state after this one; none after FINAL. */
    public State next() {
        return switch (this) {
            case DRAFT -> REVIEW;
            case REVIEW -> FINAL;
            case FINAL -> null;
        };
    }

    /** The state a step back from this one; none from DRAFT, and none from FINAL as it is terminal. */
    public State previous() {
        return switch (this) {
            case DRAFT -> null;
            case REVIEW -> DRAFT;
            case FINAL -> null;
        };
    }

    /** The state with this name; refuses anything other than DRAFT, REVIEW or FINAL. */
    public static State parse(String value) {
        try {
            return State.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("state must be DRAFT, REVIEW or FINAL, got " + value);
        }
    }
}
