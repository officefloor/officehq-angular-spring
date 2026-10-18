package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** The sheet's rates, by grade then from-date (the one with none first). */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
    }

    /**
     * Set a grade's rate from a date ({@code null}: from the beginning of time), replacing the one it
     * had for that from-date. The sheet is locked so two settings of the same rate go one at a time
     * (and is started when it was never loaded before).
     */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, LocalDate from, BigDecimal pct) {
        sheets.lock(sheetId, user);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from)
                .orElseGet(() -> new Rate(sheetId, grade, from, pct));
        rate.setPct(pct);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().setScale(2).toPlainString()
                + (from != null ? " from=" + from : ""));
        return saved;
    }
}
