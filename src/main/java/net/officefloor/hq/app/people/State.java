package net.officefloor.hq.app.people;

/** A person's workflow state; a person moves forward from DRAFT to REVIEW to FINAL, and FINAL is terminal. */
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

    /** The state with this name; refuses anything other than DRAFT, REVIEW or FINAL. */
    public static State parse(String value) {
        try {
            return State.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("state must be DRAFT, REVIEW or FINAL, got " + value);
        }
    }
}
