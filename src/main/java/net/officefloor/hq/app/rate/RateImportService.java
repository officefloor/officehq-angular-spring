package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sets a rate per pasted line of grade,rate,from,to (from and to may be absent or blank), each as the
 *  rate form would. Every line is checked first; the whole paste is one change, so one refused line
 *  sets nothing. */
@Service
public class RateImportService {

    /** A pasted line, read: the rate's fields (null from: from the beginning of time; null to: no end). */
    private record Line(String grade, BigDecimal rate, LocalDate from, LocalDate to) {
    }

    private final RateService rates;
    private final RollupService rollups;

    public RateImportService(RateService rates, RollupService rollups) {
        this.rates = rates;
        this.rollups = rollups;
    }

    /** Set the pasted rates in the order of their lines, each replacing the grade's rate from the same
     *  date; blank lines are skipped. The rates set, in the order of their lines. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int at = 0;
        for (String raw : text.split("\\r?\\n")) {
            at++;
            if (!raw.isBlank()) {
                lines.add(read(at, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new RateRefusedException("Nothing to import");
        }
        List<Rate> set = new ArrayList<>();
        for (Line l : lines) {
            set.add(rollups.track(sheetId, user, () -> rates.set(sheetId, user, l.grade(), l.from(), l.to(), l.rate())));
        }
        return set;
    }

    /** One line as the rate form reads it: a grade of A, B or C, a percent of 0.00 or more with up to
     *  two decimals, and a from-date and a to-date (each blank: none) with the to-date not before the
     *  from-date, each trimmed. */
    private static Line read(int at, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw new RateRefusedException("Line " + at + " needs grade,rate,from,to");
        }
        String grade = fields[0].trim();
        if (!grade.matches("[ABC]")) {
            throw new RateRefusedException("Line " + at + ": the grade must be A, B or C");
        }
        String rate = fields[1].trim();
        if (!rate.matches("\\d{1,5}(\\.\\d{1,2})?")) {
            throw new RateRefusedException("Line " + at + ": the rate must be 0.00 or more with up to two decimals");
        }
        LocalDate from = date(at, fields.length > 2 ? fields[2] : "");
        LocalDate to = date(at, fields.length > 3 ? fields[3] : "");
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("Line " + at + ": the to-date cannot be before the from-date");
        }
        return new Line(grade, new BigDecimal(rate), from, to);
    }

    private static LocalDate date(int at, String field) {
        String text = field.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new RateRefusedException("Line " + at + ": " + text + " is not a date");
        }
    }
}
