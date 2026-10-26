package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Grade;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportRatesService {

    /** An imported line, read: the grade, its percent, and its from-date and to-date (none: blank). */
    private record Line(Grade grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;

    public ImportRatesService(RateService rates) {
        this.rates = rates;
    }

    /** Set a rate per line of grade,rate,from,to, in order, each as the rate form would set it. Every line
     *  is checked first with the rate form's rules; one change: when any line is refused, nothing is set
     *  and nothing is written. Blank lines are skipped; an import with no lines is refused. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\\r?\\n")) {
            number++;
            if (raw.isBlank()) continue;
            lines.add(read(raw, number));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Import at least one line.");
        }
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) set.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        return set;
    }

    /** A line of grade,rate,from,to; the from-date and to-date may be blank or absent. */
    private static Line read(String raw, int number) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) throw refused(number, "it needs a grade, a rate, and at most a from-date and a to-date");
        Grade grade;
        try {
            grade = Grade.valueOf(fields[0].trim());
        } catch (IllegalArgumentException e) {
            throw refused(number, "the grade is not A, B or C");
        }
        BigDecimal pct;
        try {
            pct = new BigDecimal(fields[1].trim());
        } catch (NumberFormatException e) {
            throw refused(number, "the rate is not a number");
        }
        if (pct.signum() < 0 || pct.scale() > 2 || pct.precision() - pct.scale() > 7) {
            throw refused(number, "the rate must be 0.00 or more, with up to three digits and two decimals");
        }
        LocalDate from = date(fields, 2, number, "from-date");
        LocalDate to = date(fields, 3, number, "to-date");
        if (from != null && to != null && to.isBefore(from)) throw refused(number, "the to-date is before the from-date");
        return new Line(grade, pct, from, to);
    }

    private static LocalDate date(String[] fields, int at, int number, String what) {
        if (fields.length <= at || fields[at].isBlank()) return null;
        try {
            return LocalDate.parse(fields[at].trim());
        } catch (DateTimeParseException e) {
            throw refused(number, "the " + what + " is not a date (yyyy-mm-dd)");
        }
    }

    private static ResponseStatusException refused(int number, String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Line " + number + ": " + why + ".");
    }
}
