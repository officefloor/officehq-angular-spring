package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads sheets by id; a sheet never loaded before is created empty. The real clock is read only
 * here, to start a new sheet's "today"; everything else uses {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Audit audit;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets, Audit audit) {
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> create(id, user));
    }

    /** The sheet's "today": the date every decision and default date in the sheet uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(() -> LocalDate.now(clock));
    }

    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        sheet.setToday(today);
        return sheet;
    }

    /**
     * Change the sheet's clerk limit (two decimals; null for no limit) and floor (two decimals; null
     * leaves it as it is). Each change is recorded as SETTING_CHANGED key=&lt;key&gt; value=&lt;value&gt;,
     * clerk-limit first then floor, the clerk limit's value empty for no limit; setting a value it
     * already has records nothing.
     */
    @Transactional
    public Sheet setSettings(String id, String user, BigDecimal limit, BigDecimal floor) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.UNNECESSARY);
        if (!Objects.equals(sheet.getClerkLimit(), scaled)) {
            sheet.setClerkLimit(scaled);
            audit.change("SETTING_CHANGED", id, user, "key=clerk-limit",
                    "value=" + (scaled == null ? "" : scaled.toPlainString()));
        }
        if (floor != null) {
            BigDecimal scaledFloor = floor.setScale(2, RoundingMode.UNNECESSARY);
            if (scaledFloor.compareTo(sheet.getFloor()) != 0) {
                sheet.setFloor(scaledFloor);
                audit.change("SETTING_CHANGED", id, user, "key=floor", "value=" + scaledFloor.toPlainString());
            }
        }
        return sheet;
    }

    /**
     * The sheet, locked until the caller's transaction ends, so changes that must happen one at a
     * time on a sheet (such as giving out claim numbers) do; empty if the sheet was never loaded.
     */
    @Transactional
    public Optional<Sheet> lock(String id) {
        return sheets.findLockedById(id);
    }

    private Sheet create(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // Another user loaded the same new sheet at the same moment.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
