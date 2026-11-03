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

/** The rate import box: lines of grade,rate,from,to, each setting a rate exactly as the rate form
 *  would. */
@Service
public class RateImportService {

    /** The rate form's grades. */
    private static final Pattern GRADE = Pattern.compile("[A-Z]{1,3}");

    /** The rate form's percent: 0.00 or more with up to two decimals. */
    private static final Pattern PCT = Pattern.compile("\\s*\\d{1,5}(\\.\\d{1,2})?\\s*");

    /** One parsed line: the grade, the percent, the from-date (null: from the beginning of time) and
     *  the to-date (null: no end). */
    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;

    public RateImportService(RateService rates) {
        this.rates = rates;
    }

    /** Set a rate per line, in order, checking every line first with the rate form's rules; one
     *  change, so when any line is refused nothing is set and nothing is recorded. Blank lines are
     *  skipped; an import with no lines is refused. Returns how many rates were set. */
    @Transactional
    public int importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (!raw.isBlank()) {
                lines.add(parse(n, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        for (Line line : lines) {
            rates.set(sheetId, user, line.grade(), line.pct(), line.from(), line.to());
        }
        return lines.size();
    }

    /** A line of grade,rate,from,to: the from- and to-dates may be absent or blank. */
    private Line parse(int n, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused(n, "expected grade,rate,from,to");
        }
        String grade = fields[0].trim();
        if (!GRADE.matcher(grade).matches()) {
            throw refused(n, "the grade must be one to three capital letters");
        }
        if (!PCT.matcher(fields[1]).matches()) {
            throw refused(n, "the rate must be 0.00 or more, with up to two decimals");
        }
        BigDecimal pct = new BigDecimal(fields[1].trim()).setScale(2);
        LocalDate from = date(n, fields, 2, "from-date");
        LocalDate to = date(n, fields, 3, "to-date");
        if (from != null && to != null && to.isBefore(from)) {
            throw refused(n, "the to-date cannot be before the from-date");
        }
        return new Line(grade, pct, from, to);
    }

    private static LocalDate date(int n, String[] fields, int i, String what) {
        if (fields.length <= i || fields[i].isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(fields[i].trim());
        } catch (DateTimeParseException e) {
            throw refused(n, "the " + what + " must be YYYY-MM-DD");
        }
    }

    private static ResponseStatusException refused(int n, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + why);
    }
}
