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
    private final RateVersionRepository versions;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ParentRates parents;
    private final ApplicationEventPublisher events;

    public RateService(RateRepository rates, RateVersionRepository versions, SheetService sheets, Rollup rollup, ParentRates parents,
            ApplicationEventPublisher events) {
        this.parents = parents;
        this.rollup = rollup;
        this.rates = rates;
        this.versions = versions;
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

    /** The rates of the sheet's parent sheets, nearest first, up to three levels up. */
    @Transactional(readOnly = true)
    public List<List<Rate>> inherited(String sheetId) {
        return parents.of(sheetId);
    }

    /** The sheet's rates with its today, dated settings, policy date and its parent sheets' rates, to find the rate in force and the live due on any date. */
    @Transactional(readOnly = true)
    public RateTable table(String sheetId) {
        return new RateTable(list(sheetId), sheets.today(sheetId), sheets.settings(sheetId),
                sheets.policyDate(sheetId), parents.of(sheetId));
    }

    /**
     * Set a grade's rate from a from-date (null: from the beginning of time) through a to-date (null: no
     * end). When the grade already has a rate from that date, its value is kept as an older version and this
     * one becomes its newest version, the one in force.
     */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, scaled));
        } else {
            versions.save(new RateVersion(rate));
            rate.nextVersion(scaled, to);
            rates.flush();
        }
        events.publishEvent(new RateChanged(sheetId, user, grade, scaled, from, to));
        rollup.changed(sheetId, user, before);
        return rate;
    }

    /** An amount times a percent, divided by 100, rounded as given to two decimals; null with no rate. */
    public static BigDecimal due(BigDecimal amount, BigDecimal pct, RoundingMode rounding) {
        return pct == null ? null : amount.multiply(pct).divide(HUNDRED).setScale(2, rounding);
    }
}
