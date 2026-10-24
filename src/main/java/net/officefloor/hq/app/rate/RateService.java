package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates each grade has on each sheet, each from its from-date on (or from the beginning of time)
 * through its to-date (or with no end).
 * Setting a grade's rate for a from-date it already has a rate for replaces that rate. */
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

    /** The sheet's rates, by grade, then by from-date. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAndFrom(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = (from == null ? rates.findUndated(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from)).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, pct));
        } else {
            rate.setPct(pct);
            rate.setTo(to);
            rates.flush();
        }
        List<String> fields = new ArrayList<>(List.of("grade=" + grade,
                "rate=" + rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        if (from != null) fields.add("from=" + from);
        if (to != null) fields.add("to=" + to);
        audit.change("RATE_SET", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return rate;
    }
}
