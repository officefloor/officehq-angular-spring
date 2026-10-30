package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A sheet's dated settings: the value of each in force on a date is the one with the latest from-date on
 * or before it (a value with no from-date applying from the beginning of time); before every from-date,
 * and where that value was saved blank, the sheet's starting value (1.00 for the floor, 0.00 for the late
 * percent, 14 days for the terms; none for a grade's grace days, the clerk limit, the cap, the proof threshold, a grade's proof threshold and the note threshold; the sheet's terms for a grade's own
 * terms).
 */
public record DatedSettings(List<SheetSetting> values) {

    public static final String CLERK_LIMIT = "clerk-limit";

    public static final String FLOOR = "floor";

    public static final String TERMS = "terms";

    public static final String LATE_PCT = "late-pct";

    public static final String CAP = "cap";

    public static final String PROOF_THRESHOLD = "proof-threshold";

    public static final String NOTE_THRESHOLD = "note-threshold";

    /** The sheet's pot, shared out over each balanced pool; not dated. */
    public static final String POT = "pot";

    /** The id of the sheet's parent sheet, whose rates a grade with none in force takes; not dated. */
    public static final String PARENT = "parent";

    /** A sheet with no value saved: every setting has its starting value on every date. */
    public static final DatedSettings NONE = new DatedSettings(List.of());

    public static final String GRACE = "grace";

    /** The key of a grade's grace days: grace-A, grace-B or grace-C. */
    public static String gradeGraceKey(String grade) {
        return GRACE + "-" + grade;
    }

    /** The key of a grade's proof threshold: proof-threshold-A, proof-threshold-B or proof-threshold-C. */
    public static String gradeProofThresholdKey(String grade) {
        return PROOF_THRESHOLD + "-" + grade;
    }

    /** The key of a grade's own terms: terms-A, terms-B or terms-C. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /** The key's value in force on the date as written; empty when none is (or it was saved blank). */
    public Optional<String> on(String key, LocalDate date) {
        return values.stream()
                .filter(v -> v.getKey().equals(key))
                .filter(v -> v.getFromDate() == null || !v.getFromDate().isAfter(date))
                .max(Comparator.comparing(SheetSetting::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SheetSetting::getValue);
    }

    /** The least live due a claim dated on the date has. */
    public BigDecimal floor(LocalDate date) {
        return on(FLOOR, date).map(BigDecimal::new).orElse(Sheet.DEFAULT_FLOOR);
    }

    /** The percent an overdue claim gains of its figure for a 30-day boundary past its due-by falling on the date. */
    public BigDecimal latePct(LocalDate date) {
        return on(LATE_PCT, date).map(BigDecimal::new).orElse(Sheet.DEFAULT_LATE_PCT);
    }

    /** The sheet's terms for a claim dated on the date, in days. */
    public int terms(LocalDate date) {
        return on(TERMS, date).map(Integer::valueOf).orElse(Sheet.DEFAULT_TERMS);
    }

    /** The grade's own terms on the date; null when it has none then (it uses the sheet's). */
    public Integer gradeTerms(String grade, LocalDate date) {
        return grade == null ? null : on(gradeTermsKey(grade), date).map(Integer::valueOf).orElse(null);
    }

    /**
     * The grade's grace days on the date: the days past a claim's due-by before it is overdue; 0 when the
     * grade has none then (or it was saved blank).
     */
    public int grace(String grade, LocalDate date) {
        return grade == null ? 0 : on(gradeGraceKey(grade), date).map(Integer::valueOf).orElse(0);
    }

    /** The terms that fix the due-by of a claim of the grade dated on the date: the grade's own, or the sheet's. */
    public int termsFor(String grade, LocalDate date) {
        Integer own = gradeTerms(grade, date);
        return own != null ? own : terms(date);
    }

    /**
     * The terms that fix the due-by of a claim dated on the date for a person with their own terms (null:
     * none) and the grade then: the person's own, else the grade's, else the sheet's.
     */
    public int termsFor(Integer personTerms, String grade, LocalDate date) {
        return personTerms != null ? personTerms : termsFor(grade, date);
    }

    /** The most due a clerk may settle on a claim on the date; null when there is no limit. */
    public BigDecimal clerkLimit(LocalDate date) {
        return on(CLERK_LIMIT, date).map(BigDecimal::new).orElse(null);
    }

    /** The most due a claim dated on the date may have to be settled or approved with no proof; null when there is none. */
    public BigDecimal proofThreshold(LocalDate date) {
        return on(PROOF_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The grade's proof threshold on the date; null when it has none then (it uses the sheet's). */
    public BigDecimal gradeProofThreshold(String grade, LocalDate date) {
        return grade == null ? null : on(gradeProofThresholdKey(grade), date).map(BigDecimal::new).orElse(null);
    }

    /** The proof threshold for a claim of the grade dated on the date: the grade's own, or the sheet's; null when neither. */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        BigDecimal own = gradeProofThreshold(grade, date);
        return own != null ? own : proofThreshold(date);
    }

    /** The most due a claim dated on the date may have to be opened with an empty note; null when there is none. */
    public BigDecimal noteThreshold(LocalDate date) {
        return on(NOTE_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The most a person's due-total comes to on the date; null when there is no cap. */
    public BigDecimal cap(LocalDate date) {
        return on(CAP, date).map(BigDecimal::new).orElse(null);
    }

    /** The sheet's pot (not dated: it holds on every date); null when there is none. */
    public BigDecimal pot() {
        return on(POT, LocalDate.MAX).map(BigDecimal::new).orElse(null);
    }

    /** The id of the sheet's parent sheet (not dated); null when it has none. */
    public String parent() {
        return on(PARENT, LocalDate.MAX).orElse(null);
    }
}
