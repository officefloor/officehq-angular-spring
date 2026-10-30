package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Import rates: lines of {@code grade,rate,from,to} (from and to may be left out or blank). Every line is first
 * held to the rate form's rules, and the whole import is one change: a refused line sets nothing. Otherwise each
 * line is set in order exactly as the rate form sets it, replacing a rate for the same grade and from-date.
 */
@Service
public class RateImportService {

    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern PCT = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService service;
    private final SheetRepository sheetRows;

    public RateImportService(RateService service, SheetRepository sheetRows) {
        this.service = service;
        this.sheetRows = sheetRows;
    }

    /** Set a rate per line, in order; returns how many were set. */
    @Transactional
    public int importRates(String sheetId, String user, String text) {
        sheetRows.lockById(sheetId);
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            lines.add(parse(raw));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paste at least one line");
        }
        for (Line line : lines) {
            service.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct());
        }
        return lines.size();
    }

    private static Line parse(String raw) {
        String[] f = raw.split(",", -1);
        if (f.length < 2 || f.length > 4) throw refused(raw, "give a grade, a rate, and optionally a from- and to-date");
        String grade = f[0].trim();
        if (!GRADE.matcher(grade).matches()) throw refused(raw, "the grade must be A, B or C");
        String pct = f[1].trim();
        if (!PCT.matcher(pct).matches()) throw refused(raw, "the rate must be 0.00 or more, with up to two decimals");
        LocalDate from = f.length > 2 ? date(raw, f[2], "from-date") : null;
        LocalDate to = f.length > 3 ? date(raw, f[3], "to-date") : null;
        if (from != null && to != null && to.isBefore(from)) throw refused(raw, "the to-date is before the from-date");
        return new Line(grade, new BigDecimal(pct), from, to);
    }

    private static LocalDate date(String raw, String field, String what) {
        if (field.isBlank()) return null;
        try {
            return LocalDate.parse(field.trim());
        } catch (DateTimeParseException e) {
            throw refused(raw, "the " + what + " must be a date (yyyy-mm-dd)");
        }
    }

    private static ResponseStatusException refused(String line, String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Line \"" + line + "\": " + why);
    }
}
