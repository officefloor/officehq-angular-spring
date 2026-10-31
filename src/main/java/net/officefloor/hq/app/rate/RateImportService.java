package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The rate import box: a rate per pasted line, set as the rate form would set it. */
@Service
public class RateImportService {

    /** The grades the rate form takes. */
    private static final Pattern GRADE = Pattern.compile("[ABC]");

    /** A rate the rate form takes: a percent of 0.00 or more, up to 999.99, with up to two decimals. */
    private static final Pattern RATE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private final RateService rates;

    public RateImportService(RateService rates) {
        this.rates = rates;
    }

    /** A pasted line read into a rate's values. */
    private record Line(String grade, BigDecimal rate, LocalDate from, LocalDate to) {
    }

    /**
     * Set a rate per line of {@code text} (grade,rate,from,to; the from- and to-date may be absent or blank), in
     * order, each as the rate form would: replacing the grade's rate from the same from-date. Every line is checked
     * first by the rate form's rules: when any line is refused, nothing is set and nothing is recorded. Blank lines
     * are skipped; a paste with no lines is refused.
     */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (!raw.isBlank()) {
                lines.add(read(raw));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line");
        }
        List<Rate> done = new ArrayList<>();
        for (Line line : lines) {
            done.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.rate()));
        }
        return done;
    }

    private static Line read(String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused("Each line is grade,rate,from,to");
        }
        String grade = fields[0].trim();
        if (!GRADE.matcher(grade).matches()) {
            throw refused("A grade is A, B or C");
        }
        String rate = fields[1].trim();
        if (!RATE.matcher(rate).matches()) {
            throw refused("A rate is 0.00 or more, with up to two decimals");
        }
        LocalDate from = date(fields.length > 2 ? fields[2] : "");
        LocalDate to = date(fields.length > 3 ? fields[3] : "");
        if (from != null && to != null && to.isBefore(from)) {
            throw refused("A rate cannot end before it starts");
        }
        return new Line(grade, new BigDecimal(rate), from, to);
    }

    private static LocalDate date(String field) {
        String text = field.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw refused("A date is yyyy-mm-dd");
        }
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
