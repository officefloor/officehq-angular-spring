package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sets a rate per pasted line of {@code grade,rate,from,to}, in order, exactly as the rate form sets it;
 * the from- and to-dates may be blank or left out. Every line is held to the rate form's rules before any
 * is set: when any line is refused no rate is set, and nothing is recorded. Blank lines are skipped. */
@Service
public class RateImportService {

    /** A grade as the rate form takes it. */
    private static final Pattern GRADE = Pattern.compile("\\s*[ABC]\\s*");

    /** A percent as the rate form takes it. */
    private static final Pattern PCT = Pattern.compile("\\s*\\d{1,5}(\\.\\d{1,2})?\\s*");

    private final RateService rates;
    private final SheetRepository sheets;

    public RateImportService(RateService rates, SheetRepository sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    /** The rates set, in the order of their lines. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        List<Line> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length < 2 || fields.length > 4 || !GRADE.matcher(fields[0]).matches()
                    || !PCT.matcher(fields[1]).matches()) {
                throw new RateImportException();
            }
            LocalDate from = dateOf(fields, 2);
            LocalDate to = dateOf(fields, 3);
            if (from != null && to != null && to.isBefore(from)) throw new RateImportException();
            lines.add(new Line(fields[0].trim(), new BigDecimal(fields[1].trim()).setScale(2), from, to));
        }
        if (lines.isEmpty()) throw new RateImportException();
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) {
            set.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        }
        return set;
    }

    /** The line's date in the given field; null when it is blank or left out. */
    private static LocalDate dateOf(String[] fields, int index) {
        if (fields.length <= index || fields[index].isBlank()) return null;
        try {
            return LocalDate.parse(fields[index].trim());
        } catch (DateTimeParseException e) {
            throw new RateImportException();
        }
    }
}
