package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets) {
        this.sheets = sheets;
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

    /** Change the sheet's today. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setToday(today);
        return sheet;
    }
}
