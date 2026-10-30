package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rate for each grade on a sheet, and the due it makes of an amount. */
@Service
public class RateService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RateRepository rates;
    private final SheetService sheets;
    private final ApplicationEventPublisher events;

    public RateService(RateRepository rates, SheetService sheets, ApplicationEventPublisher events) {
        this.rates = rates;
        this.sheets = sheets;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** The percent for each grade that has a rate on the sheet. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> byGrade(String sheetId) {
        return list(sheetId).stream().collect(Collectors.toMap(Rate::getGrade, Rate::getPct));
    }

    /** Set a grade's rate, replacing the one it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2, RoundingMode.HALF_UP);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, scaled));
        } else {
            rate.setPct(scaled);
            rates.flush();
        }
        events.publishEvent(new RateChanged(sheetId, user, grade, scaled));
        return rate;
    }

    /** An amount times a percent, divided by 100, rounded half up to two decimals; null with no rate. */
    public static BigDecimal due(BigDecimal amount, BigDecimal pct) {
        return pct == null ? null : amount.multiply(pct).divide(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }
}
