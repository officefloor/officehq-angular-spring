package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.grade.GradeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rate import box: lines of grade,rate,from,to each set a grade's rate, in order, as the rate form
 * would (replacing any the grade had from the same date); the from- and to-date may be absent or blank.
 * Every line is first held to the rate form's rules; it is one change, so when any line is refused
 * nothing is set and nothing is recorded.
 */
@Service
public class RateImportService {

    /** The most lines one import takes. */
    static final int LINES_MAX = 1000;

    /** A percent as the rate form takes it: 0.00 or more, with up to two decimals. */
    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("\\d{1,5}(\\.\\d{1,2})?");

    /** A line read from the paste, checked against the form's rules. */
    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;

    public RateImportService(RateService rates) {
        this.rates = rates;
    }

    /** Set a rate per line of the text, in order; refused as a whole when any line is refused. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(line(i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of grade,rate,from,to.");
        }
        if (lines.size() > LINES_MAX) {
            throw refused("Paste at most " + LINES_MAX + " lines at once.");
        }
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) {
            set.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        }
        return set;
    }

    private static Line line(int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length < 2) {
            throw refused("Line " + number + " needs at least a grade and a rate.");
        }
        if (fields.length > 4) {
            throw refused("Line " + number + " has more than grade,rate,from,to.");
        }
        String grade = fields[0].trim();
        if (!grade.matches(GradeService.CODE)) {
            throw refused("Line " + number + ": the grade must be one to three capital letters.");
        }
        String pct = fields[1].trim();
        if (!PERCENT.matcher(pct).matches()) {
            throw refused("Line " + number + ": the rate must be 0.00 or more, with up to two decimals.");
        }
        LocalDate from = date(number, "from", fields.length > 2 ? fields[2] : "");
        LocalDate to = date(number, "to", fields.length > 3 ? fields[3] : "");
        if (from != null && to != null && to.isBefore(from)) {
            throw refused("Line " + number + ": the to-date cannot be before the from-date.");
        }
        return new Line(grade, new BigDecimal(pct), from, to);
    }

    private static LocalDate date(int number, String which, String field) {
        String value = field.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw refused("Line " + number + ": the " + which + "-date must be yyyy-mm-dd.");
        }
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
