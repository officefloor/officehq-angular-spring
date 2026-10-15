package net.officefloor.hq.app.people;

/**
 * A person's workflow state; a person moves forward from DRAFT to REVIEW to FINAL, and back a step from REVIEW to DRAFT. A FINAL
 * person cannot move forward or back, but may be reopened to REWORK, from which they move forward to FINAL again.
 */
public enum State {
    DRAFT, REVIEW, FINAL, REWORK;

    /** The state after this one; none after FINAL, and FINAL again after REWORK. */
    public State next() {
        return switch (this) {
            case DRAFT -> REVIEW;
            case REVIEW -> FINAL;
            case FINAL -> null;
            case REWORK -> FINAL;
        };
    }

    /** The state a step back from this one; none from DRAFT, none from FINAL as it is only reopened, and none from REWORK. */
    public State previous() {
        return switch (this) {
            case DRAFT -> null;
            case REVIEW -> DRAFT;
            case FINAL -> null;
            case REWORK -> null;
        };
    }

    /** The state a person is reopened to; only a FINAL person may be reopened, to REWORK. */
    public State reopened() {
        return this == FINAL ? REWORK : null;
    }

    /** The state with this name; refuses anything other than DRAFT, REVIEW, FINAL or REWORK. */
    public static State parse(String value) {
        try {
            return State.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("state must be DRAFT, REVIEW, FINAL or REWORK, got " + value);
        }
    }
}
