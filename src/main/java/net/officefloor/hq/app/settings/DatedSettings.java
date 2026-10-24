package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The values in force of a sheet's dated settings: on a date, the value recorded with the latest
 * from-date on or before it, and before every from-date the sheet's starting value (floor 1.00, late
 * percent 0.00, terms 14 days; no clerk limit, no cap, and no grade's own terms). A claim's floor, late
 * percent and terms are those in force on its date; the clerk limit and the cap those on the sheet's
 * today. */
@Service
public class DatedSettings {

    public static final String FLOOR = "floor";
    public static final String LATE_PCT = "late-pct";
    public static final String TERMS = "terms";
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String CAP = "cap";

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

    /** The clerk limit in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return valueOn(sheetId, CLERK_LIMIT, today(sheetId)).map(BigDecimal::new).orElse(null);
    }

    /** The cap in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return valueOn(sheetId, CAP, today(sheetId)).map(BigDecimal::new).orElse(null);
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
