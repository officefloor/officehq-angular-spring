package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The rate set for each grade on a sheet. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final Rollup rollup;

    public RateService(RateRepository rates, SheetService sheets, Audit audit, Rollup rollup) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The rates set on the sheet, by grade, each grade's in the order they take effect. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetId(sheetId).stream()
                .sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate.BY_FROM))
                .toList();
    }

    /** Set a grade's rate from a date (null: from the beginning of time) through a to-date (null: it
     *  does not end), replacing the rate it had from that same date, if any. A to-date before the
     *  from-date is refused. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate cannot end before it starts");
        }
        sheets.load(sheetId, user);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Rollup.Figures before = rollup.of(sheetId);
        Rate row = rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFromDate(), from))
                .findFirst()
                .orElseGet(() -> new Rate(sheetId, grade, from, scaled));
        row.setRate(scaled);
        row.setToDate(to);
        Rate saved = rates.saveAndFlush(row);
        audit.recordChangeOnCommit(sheetId, user, "RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getRate().toPlainString()
                + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
