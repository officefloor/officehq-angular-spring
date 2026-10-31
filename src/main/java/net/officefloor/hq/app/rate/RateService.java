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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Set a grade's rate from a date (null: from the beginning of time), replacing the rate it had
     *  from that same date, if any. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal rate) {
        sheets.load(sheetId, user);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Rollup.Figures before = rollup.of(sheetId);
        Rate row = rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFromDate(), from))
                .findFirst()
                .orElseGet(() -> new Rate(sheetId, grade, from, scaled));
        row.setRate(scaled);
        Rate saved = rates.saveAndFlush(row);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getRate().toPlainString()
                + (from == null ? "" : " from=" + from));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
