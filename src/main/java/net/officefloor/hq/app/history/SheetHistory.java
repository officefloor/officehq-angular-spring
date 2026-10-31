package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.Optional;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A sheet's history: after each change, what the sheet holds is kept under the sheet's today, replacing what was kept
 * under that day before. The sheet as it stood at the end of a date is then the last kept under a day on or before
 * it: every change made while the sheet's today was on or before that date, and none made later.
 */
@Service
public class SheetHistory {

    private final SheetSnapshotRepository snapshots;
    private final SheetRepository sheets;
    private final RateRepository rates;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final JsonMapper json;

    public SheetHistory(SheetSnapshotRepository snapshots, SheetRepository sheets, RateRepository rates,
            PersonRepository people, ClaimRepository claims, JsonMapper json) {
        this.snapshots = snapshots;
        this.sheets = sheets;
        this.rates = rates;
        this.people = people;
        this.claims = claims;
        this.json = json;
    }

    /** Keep what the sheet holds once the change being made is done: just before the current transaction commits,
     *  once however many changes it records; outside a transaction, at once. */
    public void keepOnCommit(String sheetId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            keep(sheetId);
            return;
        }
        String key = SheetHistory.class.getName() + ":" + sheetId;
        if (TransactionSynchronizationManager.hasResource(key)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!readOnly) {
                    keep(sheetId);
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(key);
            }
        });
    }

    /** Keep what the sheet holds now under its today. */
    @Transactional
    public void keep(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        if (sheet == null) {
            return;
        }
        SheetState state = SheetState.of(sheet, rates.findBySheetId(sheetId), people.findBySheetIdOrderByIdAsc(sheetId),
                claims.findBySheetIdOrderByListedDescIdDesc(sheetId));
        snapshots.deleteBySheetIdAndAsOf(sheetId, sheet.getToday());
        snapshots.saveAndFlush(new SheetSnapshot(sheetId, sheet.getToday(), json.writeValueAsString(state)));
    }

    /** The sheet as it stood at the end of a date; empty when no change was made on it by then. */
    @Transactional(readOnly = true)
    public Optional<SheetState> asOf(String sheetId, LocalDate date) {
        return snapshots.findFirstBySheetIdAndAsOfLessThanEqualOrderByAsOfDescIdDesc(sheetId, date)
                .map(s -> json.readValue(s.getState(), SheetState.class));
    }
}
