package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.history.AsOf;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to the sheet's own for its consolidated
 *  figures. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The descendants' people count, and their due- and overdue-totals (two decimals), added up; then the
     *  sheet's own, for a screen showing the sheet as of a date to consolidate with. Each sheet as it stood at the
     *  end of that date, with it as the sheet's today, when the screen views the sheet as of one. */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal, int ownPeopleCount,
            String ownDueTotal, String ownOverdueTotal) {
    }

    private final Rollup rollup;

    public ConsolidatedController(Rollup rollup) {
        this.rollup = rollup;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ConsolidatedView figures(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String sid = sheetId.trim();
        Rollup.Consolidated c = rollup.descendants(sid, asOf);
        Rollup.Snapshot own = asOf == null ? rollup.take(sid) : rollup.take(sid, asOf);
        return new ConsolidatedView(c.peopleCount(), c.dueTotal().toPlainString(), c.overdueTotal().toPlainString(),
                own.people().size(), own.dueTotal().toPlainString(), own.overdueTotal().toPlainString());
    }
}
