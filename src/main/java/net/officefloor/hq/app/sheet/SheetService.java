package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads sheets by id; a sheet that was never loaded before is started empty, its today set to the
 * real date. From then on the sheet's today, not the clock, is the date everything on it uses:
 * call {@link #today(String)} wherever a decision or a default depends on the date.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final SettingsService settings;
    private final Audit audit;
    private final ClaimRepository claims;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodService periods,
            SettingsService settings, Audit audit, ClaimRepository claims) {
        this.sheets = sheets;
        this.claims = claims;
        this.rollup = rollup;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(clock), LocalDate.now(clock)));
            } catch (DataIntegrityViolationException e) {
                // Another user started the same sheet at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today: the date every date-dependent decision and default on it uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** The date a claim left without a date is dated (see {@link Sheet#entryDateInForce()}). */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String id) {
        return sheets.findById(id).map(Sheet::entryDateInForce).orElseThrow(SheetNotFoundException::new);
    }

    /** Whether a claim opened now on the sheet is numbered within its period (see {@link Sheet#numbersByPeriod()}). */
    @Transactional(readOnly = true)
    public boolean numbersByPeriod(String id) {
        return sheets.findById(id).map(Sheet::numbersByPeriod).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today, releasing every claim's lock (see {@code ClaimService#lock}) and closing
     * the open periods its grace days have passed (see {@link PeriodService#closeOverdue}); the figures it moves are recorded as {@code user}'s change.
     * A change to another date first records a SNAPSHOT line per person, in list order, with their due-total on the day being left. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(id);
        if (!today.equals(sheet.getToday())) {
            before.people().values().forEach(figures -> audit.change("SNAPSHOT", id, user,
                    "name=" + figures.name(), "due-total=" + figures.dueTotal()));
            claims.unlockBySheetId(id);
        }
        sheet.setToday(today);
        sheets.flush();
        periods.closeOverdue(id, user);
        rollup.recordSince(id, user, before);
        return sheet;
    }

    /** Archive the sheet; only a steward may, and an archived sheet is not archived again. */
    @Transactional
    public Sheet archive(String id, String user) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        if (sheet.isArchived()) throw new SheetArchivedException();
        if (settings.roleOf(id, user) != Role.STEWARD) throw new SheetNotStewardException();
        sheet.setArchived(true);
        audit.change("SHEET_ARCHIVED", id, user);
        return sheet;
    }

    /** Whether the sheet is archived; a sheet never loaded is not. */
    @Transactional(readOnly = true)
    public boolean archived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** The next claim number on the sheet: 1 for its first, then 2, and so on, never given twice. */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.advanceClaimNumber(id) == 0) throw new SheetNotFoundException();
        return sheets.lastClaimNumber(id);
    }

    /** The next number in the sheet's sequence named {@code key}: 1 for its first, then 2, and so on,
     * never given twice. */
    @Transactional
    public int nextInSequence(String id, String key) {
        if (sheets.lockSheet(id) == 0) throw new SheetNotFoundException();
        if (sheets.advanceSequence(id, key) == 0) sheets.startSequence(id, key);
        return sheets.lastInSequence(id, key);
    }
}
