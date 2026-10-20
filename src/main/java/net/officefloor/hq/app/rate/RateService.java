package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RateService {

    private final RateRepository rates;
    private final RateLogRepository log;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public RateService(RateRepository rates, RateLogRepository log, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.rates = rates;
        this.log = log;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's rates by grade, then from-date; a grade without one has no rate. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return rates.findBySheetId(sheetId).stream()
                .sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate.BY_FROM))
                .toList();
    }

    /** Set the rate for a grade from a from-date (none: from the beginning of time) through a to-date (none: with
     * no end), replacing any it had from that date; each rate set is logged with the sheet's today. A to-date before the from-date is refused. */
    @Transactional
    public Rate set(String user, String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        LocalDate today = sheets.today(sheetId);
        if (to != null && from != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The to-date is before the from-date");
        }
        BigDecimal value = pct.setScale(2);
        Snapshot before = rollup.snapshot(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, value));
        } else {
            rate.setRate(value);
            rate.setTo(to);
            rates.flush();
        }
        log.saveAndFlush(new RateLog(rate, today));
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + value.toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        rollup.recordChanges(user, sheetId, before);
        return rate;
    }
}
