package net.officefloor.hq.app.person;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** A person's grade: a code of one to three capital letters. Every sheet has A, B and C; a sheet may add
 *  more of its own (see {@link GradeService#gradesOf(String)}). One instance per code, so grades compare
 *  by identity as well as by {@link #equals(Object)}. */
public final class Grade implements Comparable<Grade> {

    private static final Pattern CODE = Pattern.compile("[A-Z]{1,3}");
    private static final Map<String, Grade> CODES = new ConcurrentHashMap<>();

    public static final Grade A = valueOf("A");
    public static final Grade B = valueOf("B");
    public static final Grade C = valueOf("C");

    /** The grades every sheet has, in order. */
    public static final List<Grade> BUILT_IN = List.of(A, B, C);

    private final String code;

    private Grade(String code) {
        this.code = code;
    }

    /** Whether the text is a grade's code: one to three capital letters. */
    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** The grade of the code; refused (IllegalArgumentException) for anything but one to three capital letters. */
    @JsonCreator
    public static Grade valueOf(String code) {
        if (!isCode(code)) throw new IllegalArgumentException("\"" + code + "\" is not a grade.");
        return CODES.computeIfAbsent(code, Grade::new);
    }

    @JsonValue
    public String name() {
        return code;
    }

    @Override
    public int compareTo(Grade other) {
        return code.compareTo(other.code);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Grade g && g.code.equals(code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return code;
    }
}
