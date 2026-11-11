package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets a rate per pasted line of grade,rate,from,to, in order, each as the rate form would (the from- and to-dates may
 * be absent or blank): replacing the grade's rate with the same from-date. Every line is checked first with the rate
 * form's rules. The whole is one change: if any line is refused, no rate is set.
 */
@Service
public class ImportRatesService {

    private record Line(int number, String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    private final RateService rates;

    public ImportRatesService(RateService rates) {
        this.rates = rates;
    }

    /** How many rates were set. */
    @Transactional
    public int importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] rows = text.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parse(i + 1, rows[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new ImportRatesException("Paste at least one line of grade,rate,from,to");
        }
        // Each rate is set as the rate form sets it; a refused one rolls back those set before it.
        for (Line line : lines) {
            rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct());
        }
        return lines.size();
    }

    private static Line parse(int number, String row) {
        String[] fields = row.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw new ImportRatesException(number, "expected grade,rate,from,to");
        }
        String grade = fields[0].trim();
        if (!grade.matches("[ABC]")) {
            throw new ImportRatesException(number, "the grade is not A, B or C");
        }
        String pct = fields[1].trim();
        if (!pct.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new ImportRatesException(number, "the rate is not 0.00 to 999.99 with at most two decimals");
        }
        LocalDate from = date(number, "from-date", fields.length > 2 ? fields[2] : "");
        LocalDate to = date(number, "to-date", fields.length > 3 ? fields[3] : "");
        if (from != null && to != null && to.isBefore(from)) {
            throw new ImportRatesException(number, "the to-date is before the from-date");
        }
        return new Line(number, grade, new BigDecimal(pct).setScale(2, RoundingMode.UNNECESSARY), from, to);
    }

    private static LocalDate date(int number, String what, String value) {
        if (value.isBlank()) {
            return null;
        }
        if (!value.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ImportRatesException(number, "the " + what + " is not a yyyy-mm-dd date");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ImportRatesException(number, "the " + what + " is not a date");
        }
    }
}
