package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Loads a sheet by id; a sheet never loaded before is started empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;

    public SheetService(SheetRepository sheets, RollupService rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // another user started the same sheet at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today, for any date-dependent decision or default date on it. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The sheet's clerk limit: the most a clerk may settle a claim's due at; null for no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return sheets.findById(id).map(Sheet::getClerkLimit).orElse(null);
    }

    /**
     * The next claim number on the sheet: 1, then 2, and so on, with no gaps and never reused. The
     * sheet stays locked until the caller's transaction ends, so two claims opened at the same
     * moment take different numbers.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        Sheet sheet = sheets.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        int number = sheet.nextClaimNumber();
        sheets.saveAndFlush(sheet);
        return number;
    }

    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        // changing today has no audit line of its own, only the figures it moves
        rollup.recordChanges(id, user, before);
        return saved;
    }
}
