package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates for each grade on a sheet, each from its from-date, and the due a rate makes of an amount. */
@Service
public class RateService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public RateService(RateRepository rates, SheetService sheets, Rollup rollup, ApplicationEventPublisher events) {
        this.rollup = rollup;
        this.rates = rates;
        this.sheets = sheets;
        this.events = events;
    }

    /** The sheet's rates by grade, each grade's earliest first (one with no from-date before all). */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetId(sheetId).stream()
                .sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate::getFromDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The sheet's rates with its today, floor, terms and late percent, to find the rate in force and the live due on any date. */
    @Transactional(readOnly = true)
    public RateTable table(String sheetId) {
        return new RateTable(list(sheetId), sheets.today(sheetId), sheets.floor(sheetId),
                sheets.terms(sheetId), sheets.latePct(sheetId));
    }

    /** Set a grade's rate from a from-date (null: from the beginning of time), replacing the one it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, scaled));
        } else {
            rate.setPct(scaled);
            rates.flush();
        }
        events.publishEvent(new RateChanged(sheetId, user, grade, scaled, from));
        rollup.changed(sheetId, user, before);
        return rate;
    }

    /** An amount times a percent, divided by 100, rounded half to even to two decimals; null with no rate. */
    public static BigDecimal due(BigDecimal amount, BigDecimal pct) {
        return pct == null ? null : amount.multiply(pct).divide(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
    }
}
