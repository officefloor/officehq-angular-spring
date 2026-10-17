package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rates for each grade on a sheet, each in force from its from-date (or from the beginning of
 * time without one). Setting a rate for a grade and from-date that already have one replaces it.
 */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAsc(sheetId);
    }

    /** The grade's rate in force on a date: the one with the latest from-date on or before it. */
    @Transactional(readOnly = true)
    public Optional<Rate> inForce(String sheetId, String grade, LocalDate date) {
        return rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(rate -> rate.getFrom() == null || !rate.getFrom().isAfter(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2, RoundingMode.UNNECESSARY);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFrom(), from))
                .findFirst().orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, scaled));
        } else {
            rate.setRate(scaled);
        }
        List<String> fields = new ArrayList<>(List.of("grade=" + rate.getGrade(), "rate=" + rate.getRate().toPlainString()));
        if (from != null) {
            fields.add("from=" + from);
        }
        audit.change("RATE_SET", sheetId, user, fields.toArray(String[]::new));
        return rate;
    }
}
