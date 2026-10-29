package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Sets a rate for each pasted line of grade,rate,from,to (the last two may be absent or blank), all of them or none. */
@Service
public class RateImportService {

    /** A pasted line read into the rate form's fields. */
    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;
    private final SheetService sheets;

    public RateImportService(RateService rates, SheetService sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /**
     * Reads every line first by the rate form's rules, then sets each in line order as the form would, replacing a
     * rate for the same grade and from-date. One change: any line refused refuses them all, and nothing is stored or
     * audited.
     */
    @Transactional
    public List<Rate> importRates(String by, String sheetId, String text) {
        List<String> grades = sheets.load(sheetId).gradeList();
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n")) {
            n++;
            if (!raw.isBlank()) {
                lines.add(read(n, raw, grades));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of grade,rate,from,to.");
        }
        List<Rate> set = new ArrayList<>();
        for (Line l : lines) {
            set.add(rates.set(by, sheetId, l.grade(), l.from(), l.to(), l.pct()));
        }
        return set;
    }

    private Line read(int n, String raw, List<String> grades) {
        String[] f = raw.split(",", -1);
        if (f.length < 2) {
            throw refused("Line " + n + " needs at least a grade and a rate.");
        }
        if (f.length > 4) {
            throw refused("Line " + n + " has more than grade,rate,from,to.");
        }
        String grade = f[0].strip();
        if (!grades.contains(grade)) {
            throw refused("Line " + n + " has a grade that is not one of the grades.");
        }
        String pct = f[1].strip();
        if (!pct.matches("\\d{1,5}(\\.\\d{1,2})?")) {
            throw refused("Line " + n + " has a rate that is not 0.00 or more with up to two decimals.");
        }
        LocalDate from = date(n, "from-date", f.length > 2 ? f[2] : "");
        LocalDate to = date(n, "to-date", f.length > 3 ? f[3] : "");
        if (from != null && to != null && to.isBefore(from)) {
            throw refused("Line " + n + " has a to-date before its from-date.");
        }
        return new Line(grade, new BigDecimal(pct), from, to);
    }

    private static LocalDate date(int n, String what, String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw refused("Line " + n + " has a " + what + " that is not yyyy-mm-dd.");
        }
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }
}
