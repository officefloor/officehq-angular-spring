package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the rates for each grade on a sheet, each in force from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public RateService(RateRepository rates, SheetRepository sheets, Audit audit, RollupService rollup) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
    }

    /**
     * The grade's rate in force on the date: the one with the latest from-date on or before it, an
     * undated rate counting as from the beginning of time. Empty when the date is before every rate.
     */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** Set the grade's rate from the from-date (null: from the beginning of time), replacing the one it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate fromDate, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, fromDate).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, fromDate, pct);
        } else {
            rate.setPct(pct);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            throw new RateTakenException(sheetId, grade);
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (saved.getFromDate() == null ? "" : " from=" + saved.getFromDate()));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
