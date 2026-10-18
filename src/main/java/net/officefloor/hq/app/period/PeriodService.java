package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period: refused when its to-date is before its from-date, when another period of the sheet
     * has its name (ignoring case), or when it shares a date with another. The sheet is locked so two
     * additions are checked one at a time (and is started when it was never loaded before).
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.lock(sheetId, user);
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        List<Period> all = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (all.stream().anyMatch(p -> p.getName().toLowerCase(Locale.ROOT).equals(key))) throw nameInUse();
        if (all.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use");
    }
}
