package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;

    public SheetService(SheetRepository sheets, RollupService rollup) {
        this.sheets = sheets;
        this.rollup = rollup;
    }

    /** The sheet with this id, re-read from the store; a sheet never loaded before starts empty. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now()));
            } catch (DataIntegrityViolationException e) {
                // Another user created it at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /**
     * The sheet's today. Every feature that decides by the date, or defaults a date, asks this
     * rather than the real clock.
     */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return find(id).getToday();
    }

    /** Change the sheet's today; it writes no line of its own, only the figures it moves. */
    @Transactional
    public Sheet setToday(String user, String id, LocalDate today) {
        Sheet sheet = find(id);
        Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        sheets.flush();
        rollup.recordChanges(user, id, before);
        return sheet;
    }

    /**
     * Give out the sheet's next claim number. The sheet stays locked until the calling transaction
     * ends, so claims opened at the same moment get different numbers, and a rolled back
     * transaction gives its number back.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.incrementClaimNumber(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet.");
        }
        return sheets.lastClaimNumber(id);
    }

    /** Whether a claim opened now on the sheet is numbered within its period, not sheet-wide. */
    @Transactional(readOnly = true)
    public boolean numbersByPeriod(String id) {
        return find(id).numbersByPeriod();
    }

    /** Lock the sheet until the calling transaction ends, so changes that must not race take turns. */
    @Transactional
    public void lock(String id) {
        sheets.findLocked(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }

    private Sheet find(String id) {
        return sheets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }
}
