package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rate import box: lines of {@code grade,rate,from,to}, taken in order (from and to may be absent
 * or blank). Every line is held to the rate form's rules before anything is set; then each is set
 * exactly as the rate form sets it, replacing a rate for the same grade and from-date. The import is
 * one change, so a refused line sets nothing and writes nothing. Blank lines are skipped; an import
 * with no lines is refused.
 */
@Service
public class RateImportService {

    /** A percent: 0.00 to 99999.99, with up to two decimals. */
    private static final String PCT = "\\d{1,5}(\\.\\d{1,2})?";

    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;
    private final RoleService roles;

    public RateImportService(RateService rates, RoleService roles) {
        this.rates = rates;
        this.roles = roles;
    }

    /** Sets every line's rate in order: the rates set, in line order. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        roles.refuseViewer(sheetId, user);
        List<Line> lines = new ArrayList<>();
        String[] raw = text.split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(parse(i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of grade,rate,from,to.");
        }
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) {
            set.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        }
        return set;
    }

    private static Line parse(int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused("Line " + number + " needs a grade and a rate, then optionally a from-date and a to-date.");
        }
        String grade = fields[0].trim();
        if (!grade.matches("[ABC]")) {
            throw refused("Line " + number + ": choose a grade of A, B or C.");
        }
        String pct = fields[1].trim();
        if (!pct.matches(PCT)) {
            throw refused("Line " + number + ": enter a rate from 0.00 to 99999.99, with up to two decimals.");
        }
        LocalDate from = date(number, fields.length > 2 ? fields[2] : "", "from-date");
        LocalDate to = date(number, fields.length > 3 ? fields[3] : "", "to-date");
        if (from != null && to != null && to.isBefore(from)) {
            throw refused("Line " + number + ": the to-date cannot be before the from-date.");
        }
        return new Line(grade, new BigDecimal(pct).setScale(2, RoundingMode.UNNECESSARY), from, to);
    }

    private static LocalDate date(int number, String field, String what) {
        String text = field.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw refused("Line " + number + ": enter the " + what + " as yyyy-mm-dd, or leave it blank.");
        }
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
