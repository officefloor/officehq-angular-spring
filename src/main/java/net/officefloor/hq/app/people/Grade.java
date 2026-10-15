package net.officefloor.hq.app.people;

/** A person's grade, each with its weight: LOW is 1, MID is 3, HIGH is 5. */
public enum Grade {
    LOW(1), MID(3), HIGH(5);

    private final int weight;

    Grade(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** The grade with this name; refuses anything other than LOW, MID or HIGH. */
    public static Grade parse(String value) {
        try {
            return Grade.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("grade must be LOW, MID or HIGH, got " + value);
        }
    }
}
