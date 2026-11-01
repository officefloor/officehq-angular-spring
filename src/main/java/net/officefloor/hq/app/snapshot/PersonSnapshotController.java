package net.officefloor.hq.app.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The snapshot lines written for people on a sheet. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/snapshots")
public class PersonSnapshotController {

    /** A snapshot line: the person, the day being left and their due-total on it ("n/a" when none). */
    public record SnapshotView(long personId, LocalDate day, String dueTotal) {

        static SnapshotView of(PersonSnapshot snapshot) {
            return new SnapshotView(snapshot.getPersonId(), snapshot.getDay(), snapshot.getDueTotal());
        }
    }

    private final PersonSnapshotService snapshots;
    private final Rollup rollup;
    private final SheetService sheets;

    public PersonSnapshotController(PersonSnapshotService snapshots, Rollup rollup, SheetService sheets) {
        this.snapshots = snapshots;
        this.rollup = rollup;
        this.sheets = sheets;
    }

    /** Two snapshot dates compared, a line per person in list order: their name, their due-total in the snapshot
     *  written on leaving the from-date (none given: the sheet's today), and in the one written on leaving the
     *  to-date (none given: their due-total now); each line trimmed, so a missing figure at its end leaves no
     *  trailing space. */
    @GetMapping("/compare")
    @Transactional(readOnly = true)
    public List<String> compare(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate since = from == null ? sheets.today(sheetId) : from;
        Map<Long, String> then = since == null ? Map.of() : snapshots.on(sheetId, since);
        Map<Long, String> now = to == null ? null : snapshots.on(sheetId, to);
        List<String> lines = new ArrayList<>();
        rollup.of(sheetId).people().forEach((personId, figures) -> {
            StringBuilder line = new StringBuilder(figures.name());
            String was = then.get(personId);
            String is = now == null ? figures.dueTotal() : now.get(personId);
            line.append(' ').append(was == null ? "" : was).append(' ').append(is == null ? "" : is);
            lines.add(line.toString().stripTrailing());
        });
        return lines;
    }

    /** The sheet's snapshot lines, oldest first. */
    @GetMapping
    public List<SnapshotView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return snapshots.list(sheetId, asOf).stream().map(SnapshotView::of).toList();
    }
}
