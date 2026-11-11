package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final RateLogRepository logs;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public RateService(RateRepository rates, RateLogRepository logs, SheetRepository sheets, Audit audit,
            RollupService rollup) {
        this.rates = rates;
        this.logs = logs;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
    }

    /**
     * The grade's rate in force on the date: the one with the latest from-date on or before it, an
     * undated rate counting as from the beginning of time. Empty when the date is before every rate, or
     * after that rate's to-date.
     */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.appliesOn(date));
    }

    /**
     * The grade's rate percent in force on the date as the rates stood at the end of {@code asOf}: each from-date's
     * last setting made while the sheet's today was on or before it (a rate set before settings were logged counts as
     * it is now). Empty when the grade had no rate in force on the date then.
     */
    public Optional<BigDecimal> rateAsOf(String sheetId, String grade, LocalDate date, LocalDate asOf) {
        List<Rate> now = rates.findBySheetIdAndGrade(sheetId, grade);
        List<RateLog> set = logs.findBySheetIdAndGradeOrderByIdAsc(sheetId, grade);
        Map<LocalDate, RateLog> then = new LinkedHashMap<>();
        for (RateLog log : set) {
            if (!log.getSetOn().isAfter(asOf)) {
                then.put(log.getFromDate(), log);
            }
        }
        List<Rate> stood = new ArrayList<>();
        for (Rate rate : now) {
            boolean logged = set.stream().anyMatch(l -> Objects.equals(l.getFromDate(), rate.getFromDate()));
            if (!logged) {
                stood.add(rate);
            }
        }
        for (RateLog log : then.values()) {
            Rate rate = new Rate(sheetId, grade, log.getFromDate(), log.getPct());
            rate.setToDate(log.getToDate());
            stood.add(rate);
        }
        return stood.stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.appliesOn(date))
                .map(Rate::getPct);
    }

    /**
     * Set the grade's rate from the from-date (null: from the beginning of time) through the to-date (null: no
     * end), replacing the one it had; a to-date before the from-date is refused.
     */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new RateDatesException(fromDate, toDate);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, fromDate).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, fromDate, pct);
        } else {
            rate.setPct(pct);
        }
        rate.setToDate(toDate);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            throw new RateTakenException(sheetId, grade);
        }
        logs.save(new RateLog(saved, sheets.findById(sheetId).orElseThrow().getToday()));
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (saved.getFromDate() == null ? "" : " from=" + saved.getFromDate())
                + (saved.getToDate() == null ? "" : " to=" + saved.getToDate()));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
