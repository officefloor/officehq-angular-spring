package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates each grade has on each sheet, each from its from-date on (or from the beginning of time).
 * Setting a grade's rate for a from-date it already has a rate for replaces that rate. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RateService(RateRepository rates, SheetRepository sheets, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's rates, by grade, then by from-date. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAndFrom(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        Rate rate = (from == null ? rates.findUndated(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from)).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, pct));
        } else {
            rate.setPct(pct);
            rates.flush();
        }
        List<String> fields = new ArrayList<>(List.of("grade=" + grade,
                "rate=" + rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        if (from != null) fields.add("from=" + from);
        audit.change("RATE_SET", sheetId, user, fields.toArray(String[]::new));
        return rate;
    }
}
