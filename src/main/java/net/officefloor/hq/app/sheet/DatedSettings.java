package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A sheet's dated settings: the value of each in force on a date is the one with the latest from-date on
 * or before it (a value with no from-date applying from the beginning of time); before every from-date,
 * and where that value was saved blank, the sheet's starting value (1.00 for the floor, 0.00 for the late
 * percent, 14 days for the terms; none for the rounding mode, a grade's grace days, the clerk limit, a grade's clerk limit, the cap, the proof threshold, a grade's proof threshold, the note threshold and the review threshold; the sheet's terms for a grade's own
 * terms).
 */
public record DatedSettings(List<SheetSetting> values) {

    public static final String CLERK_LIMIT = "clerk-limit";

    public static final String FLOOR = "floor";

    public static final String TERMS = "terms";

    public static final String LATE_PCT = "late-pct";

    public static final String CAP = "cap";

    /** How a claim's figures round: half-up or half-even; none (blank) leaves it to the policy date. */
    public static final String ROUNDING = "rounding";

    public static final String PROOF_THRESHOLD = "proof-threshold";

    public static final String NOTE_THRESHOLD = "note-threshold";

    /** A claim whose due is above it goes to REVIEW when opened; none when blank. */
    public static final String REVIEW_THRESHOLD = "review-threshold";

    /** The sheet's pot, shared out over each balanced pool; not dated. */
    public static final String POT = "pot";

    /** A person whose due-total goes above it is alerted; not dated. */
    public static final String ALERT_THRESHOLD = "alert-threshold";

    /** A claim reminded at least this many days after its previous reminder is escalated; not dated. */
    public static final String ESCALATE_AFTER = "escalate-after";

    /** A REVIEW claim is late once its review day plus this many days is before the sheet's today; not dated. */
    public static final String REVIEW_SLA = "review-sla";

    /** A person whose score is at or above it is in the MID band (while the high threshold is set too); not dated. */
    public static final String SCORE_MID = "score-mid";

    /** A person whose score is at or above it is in the HIGH band (while the mid threshold is set too); not dated. */
    public static final String SCORE_HIGH = "score-high";

    /** While on, changing the sheet's today writes everyone's score on the day being left; on or blank (off); not dated. */
    public static final String SCORE_LOG = "score-log";

    /** A person may have at most this many OPEN or PENDING claims: opening one more is refused; not dated. */
    public static final String MAX_OPEN = "max-open";

    /** The sheet's proof kinds, comma separated (receipt, photo): each a claim above its proof threshold needs a proof of; not dated. */
    public static final String PROOF_KINDS = "proof-kinds";

    /** The grade a person added by quick-add or by an import line with a blank grade gets (A, B or C); not dated. */
    public static final String DEFAULT_GRADE = "default-grade";

    /** The id of the sheet's parent sheet, whose rates a grade with none in force takes; not dated. */
    public static final String PARENT = "parent";

    /** The month (1 to 12) the sheet's fiscal year starts in; not dated. */
    public static final String FISCAL_START_MONTH = "fiscal-start-month";

    /** The month a fiscal year starts in when none is set: January. */
    public static final int DEFAULT_FISCAL_START_MONTH = 1;

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

    /** The key of a grade's clerk limit: clerk-limit-A, clerk-limit-B or clerk-limit-C. */
    public static String gradeClerkLimitKey(String grade) {
        return CLERK_LIMIT + "-" + grade;
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

    /** The rounding mode in force on the date: half-up or half-even; null when none is (the policy date decides). */
    public RoundingMode rounding(LocalDate date) {
        return on(ROUNDING, date).map(DatedSettings::roundingMode).orElse(null);
    }

    /** A rounding mode as written (half-up or half-even); null for anything else. */
    public static RoundingMode roundingMode(String value) {
        return switch (value) {
            case "half-up" -> RoundingMode.HALF_UP;
            case "half-even" -> RoundingMode.HALF_EVEN;
            default -> null;
        };
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

    /** The grade's clerk limit on the date; null when it has none then (it uses the sheet's). */
    public BigDecimal gradeClerkLimit(String grade, LocalDate date) {
        return grade == null ? null : on(gradeClerkLimitKey(grade), date).map(BigDecimal::new).orElse(null);
    }

    /** The clerk limit for a claim of the grade on the date: the grade's own, or the sheet's; null when neither. */
    public BigDecimal clerkLimitFor(String grade, LocalDate date) {
        BigDecimal own = gradeClerkLimit(grade, date);
        return own != null ? own : clerkLimit(date);
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

    /** The most due a claim dated on the date may have to be opened straight to OPEN; null when there is none. */
    public BigDecimal reviewThreshold(LocalDate date) {
        return on(REVIEW_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The most a person's due-total comes to on the date; null when there is no cap. */
    public BigDecimal cap(LocalDate date) {
        return on(CAP, date).map(BigDecimal::new).orElse(null);
    }

    /** The sheet's pot (not dated: it holds on every date); null when there is none. */
    public BigDecimal pot() {
        return on(POT, LocalDate.MAX).map(BigDecimal::new).orElse(null);
    }

    /** The sheet's proof kinds as written, comma separated (not dated); null when none are set. */
    public String proofKindsText() {
        return on(PROOF_KINDS, LocalDate.MAX).orElse(null);
    }

    /** The sheet's proof kinds (not dated); empty when none are set. */
    public List<String> proofKinds() {
        String text = proofKindsText();
        return text == null ? List.of() : java.util.Arrays.stream(text.split(",")).map(String::trim).filter(k -> !k.isEmpty()).distinct().toList();
    }

    /** The sheet's alert threshold (not dated: it holds on every date); null when it is off. */
    public BigDecimal alertThreshold() {
        return on(ALERT_THRESHOLD, LocalDate.MAX).map(BigDecimal::new).orElse(null);
    }

    /** The sheet's escalation gap in days (not dated: it holds on every date); null when it is off. */
    public Integer escalateAfter() {
        return on(ESCALATE_AFTER, LocalDate.MAX).map(Integer::valueOf).orElse(null);
    }

    /** The sheet's review SLA in days (not dated: it holds on every date); null when there is none. */
    public Integer reviewSla() {
        return on(REVIEW_SLA, LocalDate.MAX).map(Integer::valueOf).orElse(null);
    }

    /** The sheet's mid score threshold, a whole number (not dated); null when it is blank. */
    public Integer scoreMid() {
        return on(SCORE_MID, LocalDate.MAX).map(Integer::valueOf).orElse(null);
    }

    /** The sheet's maximum of open claims per person (not dated); null when there is none. */
    public Integer maxOpen() {
        return on(MAX_OPEN, LocalDate.MAX).map(Integer::valueOf).orElse(null);
    }

    /** Whether the sheet's score log is on (not dated). */
    public boolean scoreLog() {
        return on(SCORE_LOG, LocalDate.MAX).map("on"::equals).orElse(false);
    }

    /** The sheet's high score threshold, a whole number (not dated); null when it is blank. */
    public Integer scoreHigh() {
        return on(SCORE_HIGH, LocalDate.MAX).map(Integer::valueOf).orElse(null);
    }

    /** The month (1 to 12) the sheet's fiscal year starts in (not dated); 1 when none is set. */
    public int fiscalStartMonth() {
        return on(FISCAL_START_MONTH, LocalDate.MAX).map(Integer::valueOf).orElse(DEFAULT_FISCAL_START_MONTH);
    }

    /** The sheet's default grade as set (not dated); null when none is set (grade A). */
    public String defaultGradeText() {
        return on(DEFAULT_GRADE, LocalDate.MAX).orElse(null);
    }

    /** The grade a new person gets when none is given (not dated): the default grade, else A. */
    public String defaultGrade() {
        String grade = defaultGradeText();
        return grade == null ? "A" : grade;
    }

    /** The id of the sheet's parent sheet (not dated); null when it has none. */
    public String parent() {
        return on(PARENT, LocalDate.MAX).orElse(null);
    }
}
