package net.officefloor.hq.app.sheet;

import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;
    private final Audit audit;
    // The settings read the sheet's today, so they are looked up when needed rather than injected.
    private final ObjectProvider<SettingService> settings;

    public SheetService(SheetRepository sheets, Rollup rollup, ApplicationEventPublisher events, Audit audit,
            ObjectProvider<SettingService> settings) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
    }

    /** The sheet with this id, as the store holds it now; a sheet never loaded before starts empty. */
    @Transactional
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    /**
     * The sheet's today. Every decision that depends on the date, and every default date, uses this
     * and never the real clock.
     */
    @Transactional
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /** Change the sheet's today; it has no audit line of its own: first a snapshot of each person's
     *  due-total on the day being left, then what it sets off (such as the periods it closes) and then
     *  the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id);
        Rollup.Snapshot before = rollup.snapshot(id);
        rollup.recordSnapshot(user, before);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        events.publishEvent(new TodayChanged(id, user, today));
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Archive the sheet: it stays visible, and every change on it is refused from then on (see
     *  {@code ArchiveGuard}). Refused to anyone but a steward of the sheet, and when it is archived. */
    @Transactional
    public Sheet archive(String id, String user) {
        load(id);
        Sheet sheet = sheets.findForUpdate(id).orElseThrow();
        if (settings.getObject().roleOf(id, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can archive the sheet.");
        }
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is already archived.");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /**
     * The sheet's next claim number: 1 for its first opened claim, then 2, and so on. The sheet stays
     * locked until the transaction ends, so two claims opened at once never share a number.
     */
    @Transactional
    public int nextClaimNumber(String id) {
        load(id);
        sheets.takeClaimNumber(id);
        return sheets.claimNumbersUsed(id);
    }

    /**
     * The sheet's next settle batch number: 1 for its first bulk settle that settled a claim, then 2,
     * and so on. The sheet stays locked until the transaction ends, so two batches never share a number.
     */
    @Transactional
    public int nextSettleBatch(String id) {
        load(id);
        sheets.takeSettleBatch(id);
        return sheets.settleBatchesUsed(id);
    }

    /**
     * The next number of one of the sheet's per-period sequences (by key): 1 for its first, then 2,
     * and so on. The sheet stays locked until the transaction ends, so two claims opened at once
     * never share a number.
     */
    @Transactional
    public int nextSequenceNumber(String id, String key) {
        load(id);
        sheets.findForUpdate(id);
        Integer used = sheets.sequenceNumbersUsed(id, key);
        if (used == null) {
            sheets.startSequence(id, key);
            return 1;
        }
        sheets.takeSequenceNumber(id, key);
        return used + 1;
    }

    private Sheet create(String id) {
        try {
            // The only use of the real clock: a new sheet's today starts as the real date.
            return sheets.saveAndFlush(new Sheet(id, Instant.now(), LocalDate.now()));
        } catch (DataIntegrityViolationException raced) {
            // Another user loaded the same new id at the same moment.
            return sheets.findById(id).orElseThrow(() -> raced);
        }
    }
}
