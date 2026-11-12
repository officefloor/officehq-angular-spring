package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's rates for each grade, each from a date or from the beginning of time.
 *  perhaps through a to-date. Setting a grade's rate for a from-date it already has replaces that
 *  rate, and its to-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Rollup rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetRepository sheets, Rollup rollup, Audit audit) {
        this.rollup = rollup;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        requireSheet(sheetId);
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** The rates of each of the sheet's ancestors, nearest first, each by grade: a grade with no rate in force
     *  on a date on the sheet takes the first of them with one. */
    @Transactional(readOnly = true)
    public List<List<Rate>> inherited(String sheetId) {
        requireSheet(sheetId);
        return sheets.ancestors(sheetId).stream().map(rates::findBySheetIdOrderByGradeAsc).toList();
    }

    /** The sheet's ancestors, nearest first, up to three levels up. */
    @Transactional(readOnly = true)
    public List<String> ancestors(String sheetId) {
        requireSheet(sheetId);
        return sheets.ancestors(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        requireSheet(sheetId);
        if (to != null && from != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        BigDecimal value = pct.setScale(2);
        Rollup.Snapshot before = rollup.take(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        if (rate == null) {
            try {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, value));
            } catch (DataIntegrityViolationException e) {
                // Another user set the same grade and from-date's first rate at the same moment.
                throw new RateConflictException();
            }
        } else {
            rate.setRate(value);
            rate.setToDate(to);
            rate = rates.saveAndFlush(rate);
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + rate.getRate().toPlainString()
                + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(sheetId, user, before);
        return rate;
    }

    /** One line of the rate import box: a grade's rate from a date through a date, either perhaps none. */
    private record ImportLine(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern PCT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");

    /** Set the rate each line of grade,rate,from,to names (from and to may be left out or blank), in order,
     *  each as the rate form would; every line is checked first, and one refused line refuses them all.
     *  Blank lines are skipped. How many were set. */
    @Transactional
    public int importRates(String sheetId, String user, String text) {
        requireSheet(sheetId);
        List<ImportLine> lines = new ArrayList<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(parseImport(i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new RateRefusedException("Paste at least one line of grade,rate,from,to.");
        }
        for (ImportLine line : lines) {
            set(sheetId, user, line.grade(), line.from(), line.to(), line.pct());
        }
        return lines.size();
    }

    private static ImportLine parseImport(int number, String line) {
        String[] f = line.split(",", -1);
        if (f.length < 2 || f.length > 4) {
            throw importRefused(number, "write it as grade,rate,from,to.");
        }
        String grade = f[0].trim();
        if (!GRADE.matcher(grade).matches()) {
            throw importRefused(number, "the grade must be A, B or C.");
        }
        String pct = f[1].trim();
        if (!PCT.matcher(pct).matches()) {
            throw importRefused(number, "enter a rate of 0.00 or more, with up to two decimals.");
        }
        LocalDate from = importDate(number, f.length > 2 ? f[2] : "", "from-date");
        LocalDate to = importDate(number, f.length > 3 ? f[3] : "", "to-date");
        if (from != null && to != null && to.isBefore(from)) {
            throw importRefused(number, "a rate's to-date cannot be before its from-date.");
        }
        return new ImportLine(grade, new BigDecimal(pct), from, to);
    }

    private static LocalDate importDate(int number, String field, String what) {
        String d = field.trim();
        if (d.isEmpty()) {
            return null;
        }
        try {
            if (d.length() != 10) {
                throw new DateTimeParseException(d, d, 0);
            }
            return LocalDate.parse(d);
        } catch (DateTimeParseException e) {
            throw importRefused(number, "enter a " + what + " as YYYY-MM-DD, or leave it blank.");
        }
    }

    private static RateRefusedException importRefused(int number, String why) {
        return new RateRefusedException("Line " + number + ": " + why);
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
