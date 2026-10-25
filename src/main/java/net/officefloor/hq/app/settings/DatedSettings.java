package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The values in force of a sheet's dated settings: on a date, the value recorded with the latest
 * from-date on or before it, and before every from-date the sheet's starting value (floor 1.00, late
 * percent 0.00, terms 14 days; no clerk limit, no grade's own clerk limit, no cap, no proof threshold, no grade's own proof threshold, no note threshold, no review threshold, no grade's own terms and no grade's grace days). A claim's floor, late
 * percent, terms, proof threshold, note threshold and review threshold are those in force on its date; the clerk limit and the cap those on the sheet's
 * today. */
@Service
public class DatedSettings {

    public static final String FLOOR = "floor";
    public static final String LATE_PCT = "late-pct";
    public static final String TERMS = "terms";
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";
    public static final String REVIEW_THRESHOLD = "review-threshold";
    public static final String GRACE = "grace";
    public static final String ROUNDING = "rounding";
    public static final String HALF_UP = "half-up";
    public static final String HALF_EVEN = "half-even";

    public static final BigDecimal FLOOR_START = new BigDecimal("1.00");
    public static final BigDecimal LATE_PCT_START = new BigDecimal("0.00");
    public static final int TERMS_START = 14;

    private final SheetSettingRepository settings;
    private final SheetRepository sheets;

    public DatedSettings(SheetSettingRepository settings, SheetRepository sheets) {
        this.settings = settings;
        this.sheets = sheets;
    }

    /** The key a grade's own terms are kept under. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /** The key a grade's own proof threshold is kept under. */
    public static String gradeProofThresholdKey(String grade) {
        return PROOF_THRESHOLD + "-" + grade;
    }

    /** The key a grade's own clerk limit is kept under. */
    public static String gradeClerkLimitKey(String grade) {
        return CLERK_LIMIT + "-" + grade;
    }

    /** The key a grade's grace days are kept under. */
    public static String gradeGraceKey(String grade) {
        return GRACE + "-" + grade;
    }

    /** Every value recorded for the key, oldest from-date first. */
    @Transactional(readOnly = true)
    public List<SheetSetting> history(String sheetId, String key) {
        return settings.findBySheetIdAndKeyOrderByFromAsc(sheetId, key);
    }

    @Transactional(readOnly = true)
    public BigDecimal floorOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, FLOOR, date).map(BigDecimal::new).orElse(FLOOR_START);
    }

    @Transactional(readOnly = true)
    public BigDecimal latePctOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, LATE_PCT, date).map(BigDecimal::new).orElse(LATE_PCT_START);
    }

    /** The sheet's own terms in force on the date, in days. */
    @Transactional(readOnly = true)
    public int termsOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, TERMS, date).map(Integer::valueOf).orElse(TERMS_START);
    }

    /** The grade's own terms in force on the date, in days; null when it uses the sheet's terms then. */
    @Transactional(readOnly = true)
    public Integer gradeTermsOn(String sheetId, String grade, LocalDate date) {
        return valueOn(sheetId, gradeTermsKey(grade), date).map(Integer::valueOf).orElse(null);
    }

    /** The terms a claim dated {@code date} of a person of {@code grade} is due by: the grade's own in
     * force then, or else the sheet's in force then. */
    @Transactional(readOnly = true)
    public int termsFor(String sheetId, String grade, LocalDate date) {
        Integer own = grade == null ? null : gradeTermsOn(sheetId, grade, date);
        return own != null ? own : termsOn(sheetId, date);
    }

    /** The grade's grace days in force on the date: a claim of a person of the grade then, dated then, is
     * overdue only once its due-by plus them is past; null when the grade has none then. */
    @Transactional(readOnly = true)
    public Integer gradeGraceOn(String sheetId, String grade, LocalDate date) {
        return grade == null ? null : valueOn(sheetId, gradeGraceKey(grade), date).map(Integer::valueOf).orElse(null);
    }

    /** The clerk limit in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return valueOn(sheetId, CLERK_LIMIT, today(sheetId)).map(BigDecimal::new).orElse(null);
    }

    /** The grade's own clerk limit in force on the date; null when it has none then. */
    @Transactional(readOnly = true)
    public BigDecimal gradeClerkLimitOn(String sheetId, String grade, LocalDate date) {
        return grade == null ? null : valueOn(sheetId, gradeClerkLimitKey(grade), date).map(BigDecimal::new).orElse(null);
    }

    /** The clerk limit of a claim of a person of {@code grade}: the grade's own in force on the sheet's
     * today, or else the sheet's in force then; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimitFor(String sheetId, String grade) {
        BigDecimal own = gradeClerkLimitOn(sheetId, grade, today(sheetId));
        return own != null ? own : clerkLimit(sheetId);
    }

    /** The cap in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return valueOn(sheetId, CAP, today(sheetId)).map(BigDecimal::new).orElse(null);
    }

    /** The proof threshold in force on the date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, PROOF_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The grade's own proof threshold in force on the date; null when it has none then. */
    @Transactional(readOnly = true)
    public BigDecimal gradeProofThresholdOn(String sheetId, String grade, LocalDate date) {
        return grade == null ? null : valueOn(sheetId, gradeProofThresholdKey(grade), date).map(BigDecimal::new).orElse(null);
    }

    /** The proof threshold of a claim dated {@code date} of a person of {@code grade}: the grade's own in
     * force then, or else the sheet's in force then; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdFor(String sheetId, String grade, LocalDate date) {
        BigDecimal own = gradeProofThresholdOn(sheetId, grade, date);
        return own != null ? own : proofThresholdOn(sheetId, date);
    }

    /** The note threshold in force on the date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThresholdOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, NOTE_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The review threshold in force on the date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThresholdOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, REVIEW_THRESHOLD, date).map(BigDecimal::new).orElse(null);
    }

    /** The rounding mode recorded in force on the date, as text; null when there is none. */
    @Transactional(readOnly = true)
    public String roundingValueOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, ROUNDING, date).orElse(null);
    }

    /** The rounding mode in force on the date; null when there is none. */
    @Transactional(readOnly = true)
    public RoundingMode roundingOn(String sheetId, LocalDate date) {
        return valueOn(sheetId, ROUNDING, date).map(DatedSettings::roundingMode).orElse(null);
    }

    /** The rounding mode a recorded rounding value names; null for any other. */
    public static RoundingMode roundingMode(String value) {
        return switch (value) {
            case HALF_UP -> RoundingMode.HALF_UP;
            case HALF_EVEN -> RoundingMode.HALF_EVEN;
            default -> null;
        };
    }

    /** The value of the key in force on the date; empty when none is recorded then, or the one
     * recorded is "none". */
    private Optional<String> valueOn(String sheetId, String key, LocalDate date) {
        return settings.findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(sheetId, key, date)
                .map(SheetSetting::getValue);
    }

    private LocalDate today(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
    }
}
