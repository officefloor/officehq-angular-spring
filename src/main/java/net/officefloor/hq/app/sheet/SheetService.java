package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before starts empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Clock clock;
    private final Audit audit;

    public SheetService(SheetRepository sheets, Clock clock, Audit audit) {
        this.sheets = sheets;
        this.clock = clock;
        this.audit = audit;
    }

    /** The sheet with this id, created empty when it was never loaded before. */
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, LocalDate.now(clock)));
            } catch (DataIntegrityViolationException e) {
                return sheets.findById(id).orElseThrow(() -> e); // created concurrently
            }
        });
    }

    /** The sheet's today: the date every date-dependent decision and default on the sheet uses. */
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /** Take the sheet's next claim number, holding the sheet locked until the transaction ends so
     *  claims opened at the same moment get different numbers. */
    @Transactional
    public long nextClaimNumber(String id) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        long number = sheet.nextClaimNumber();
        sheets.saveAndFlush(sheet);
        return number;
    }

    /** Take the sheet's next settle batch number, holding the sheet locked until the transaction
     *  ends so bulk settles made at the same moment get different numbers. */
    @Transactional
    public long nextBatchNumber(String id) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        long number = sheet.nextBatchNumber();
        sheets.saveAndFlush(sheet);
        return number;
    }

    /** Hold the sheet locked until the transaction ends, so changes to it made at the same moment
     *  wait their turn. */
    @Transactional
    public void lock(String id) {
        load(id);
        sheets.findLockedById(id).orElseThrow();
    }

    /** Change the sheet's today. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.save(sheet);
    }

    /** Whether the sheet is archived: every change to it is then refused. */
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet; refused when it is archived already. */
    @Transactional
    public Sheet archive(String id, String by) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        if (sheet.isArchived()) {
            throw new ArchiveRefusedException("the sheet is archived already");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + by);
        return saved;
    }
}
