package net.officefloor.hq.app.people;

/** A person's kind, each with its verge: A is 1, B is 2, C is 4. */
public enum Kind {
    A(1), B(2), C(4);

    private final int verge;

    Kind(int verge) {
        this.verge = verge;
    }

    public int verge() {
        return verge;
    }

    /** The kind with this name; refuses anything other than A, B or C. */
    public static Kind parse(String value) {
        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("kind must be A, B or C, got " + value);
        }
    }
}
