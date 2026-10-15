package net.officefloor.hq.app.people;

/** A person's kind, each with its verge: A is 2, B is 3, C is 5. */
public enum Kind {
    A(2), B(3), C(5);

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
