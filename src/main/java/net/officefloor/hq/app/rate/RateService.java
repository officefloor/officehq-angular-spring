package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final AddedGradeRepository added;
    private final AccessService access;
    private final SheetRepository sheetRows;

    public RateService(RateRepository rates, RateVersionRepository versions, SheetService sheets, Rollup rollup, ParentRates parents,
            ApplicationEventPublisher events, AddedGradeRepository added, AccessService access, SheetRepository sheetRows) {
        this.access = access;
        this.sheetRows = sheetRows;
        this.added = added;
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
     * one becomes its newest version, the one in force. Saved with a from-date on or after the sheet's today,
     * it is forward-only: it applies only to claims added after it was saved.
     */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        sheets.load(sheetId, user);
        if (!GradeService.grades(added, sheetId).contains(grade)) {
            throw new GradeRefused("No such grade: " + grade + ".");
        }
        BigDecimal scaled = pct.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        Long forwardAfter = from != null && !from.isBefore(sheets.today(sheetId)) ? rates.maxClaimId() : null;
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, scaled).forwardAfter(forwardAfter));
        } else {
            versions.save(new RateVersion(rate));
            rate.nextVersion(scaled, to);
            rate.forwardAfter(forwardAfter);
            rates.flush();
        }
        events.publishEvent(new RateChanged(sheetId, user, grade, scaled, from, to));
        rollup.changed(sheetId, user, before);
        return rate;
    }

    /**
     * Withdraw the newest version of a grade's rate from a from-date (null: from the beginning of time): the
     * version before it, when there is one, applies again; with none the rate is gone. Only a steward may;
     * anyone else, or a rate that is not set, is refused.
     */
    @Transactional
    public void withdraw(String sheetId, String user, String grade, LocalDate from) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can withdraw a rate");
        }
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rate"));
        Rollup.Figures before = rollup.figures(sheetId);
        RateVersion older = versions.findByRateIdAndVersion(rate.getId(), rate.getVersion() - 1).orElse(null);
        if (older == null) {
            rates.delete(rate);
        } else {
            rate.previousVersion(older);
            versions.delete(older);
        }
        rates.flush();
        events.publishEvent(new RateWithdrawn(sheetId, user, grade, from));
        rollup.changed(sheetId, user, before);
    }

    /** An amount times a percent, divided by 100, rounded as given to two decimals; null with no rate. */
    public static BigDecimal due(BigDecimal amount, BigDecimal pct, RoundingMode rounding) {
        return pct == null ? null : amount.multiply(pct).divide(HUNDRED).setScale(2, rounding);
    }
}
