package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.officefloor.hq.app.history.AsOf;
import net.officefloor.hq.app.history.Preview;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's summary due-totals by grade, for another sheet's screen to compare with its own. The signed-in
 *  user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/grade-due-totals")
public class GradeDueTotalsController {

    private final Rollup rollup;
    private final SheetRepository sheets;

    public GradeDueTotalsController(Rollup rollup, SheetRepository sheets) {
        this.rollup = rollup;
        this.sheets = sheets;
    }

    /** By grade, the due-totals (two decimals) of the people on it (retired people left out, a due-total of n/a
     *  adding nothing), as the sheet stood at the end of the as-of date when one is given; a grade with none
     *  absent. 404 for a sheet that does not exist. */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> gradeDueTotals(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestHeader(value = Preview.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate preview) {
        String sid = sheetId.trim();
        if (!sheets.existsById(sid)) {
            return ResponseEntity.notFound().build();
        }
        LocalDate previewed = asOf == null ? preview : null;
        Rollup.Snapshot snapshot = asOf != null ? rollup.take(sid, asOf)
                : previewed != null ? rollup.preview(sid, previewed) : rollup.take(sid);
        Map<String, String> shown = new LinkedHashMap<>();
        (previewed != null ? rollup.breakdown(sid, snapshot, previewed) : rollup.breakdown(sid, asOf, snapshot)).gradeDueTotals().forEach((g, d) -> shown.put(g, d.toPlainString()));
        return ResponseEntity.ok(shown);
    }
}
