package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imported rates: lines of grade,rate,from,to (the last two may be absent or blank), each checked
 * first with the rate form's rules, then set in order exactly as the form sets one (replacing a rate
 * for the same grade and from-date). Blank lines are skipped; a paste with no line is refused. It is
 * one change: a line that is refused refuses them all.
 */
@Service
public class RateImportService {

    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern PCT = Pattern.compile("\\d{1,5}(\\.\\d{1,2})?");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {}

    private final RateService rates;

    public RateImportService(RateService rates) {
        this.rates = rates;
    }

    /** The rates set, in the order of their lines. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            lines.add(parse(raw));
        }
        if (lines.isEmpty()) throw new RateImportRefusedException();
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) {
            set.add(rates.set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        }
        return set;
    }

    private Line parse(String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) throw new RateImportRefusedException();
        String grade = fields[0].strip();
        String pct = fields[1].strip();
        LocalDate from = date(fields.length > 2 ? fields[2].strip() : "");
        LocalDate to = date(fields.length > 3 ? fields[3].strip() : "");
        if (!GRADE.matcher(grade).matches() || !PCT.matcher(pct).matches()) throw new RateImportRefusedException();
        if (from != null && to != null && to.isBefore(from)) throw new RateImportRefusedException();
        return new Line(grade, new BigDecimal(pct).setScale(2), from, to);
    }

    /** A yyyy-mm-dd date, or null when blank. */
    private static LocalDate date(String text) {
        if (text.isEmpty()) return null;
        if (!DATE.matcher(text).matches()) throw new RateImportRefusedException();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new RateImportRefusedException();
        }
    }
}
