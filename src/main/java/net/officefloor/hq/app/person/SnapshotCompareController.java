package net.officefloor.hq.app.person;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/snapshot-compare")
public class SnapshotCompareController {

    /** A person's due-total in each of two snapshots: empty when there is none. */
    public record Line(String name, String then, String now) {
    }

    private final PersonService people;
    private final PersonSnapshotRepository snapshots;
    private final RollupService rollups;
    private final SheetService sheets;

    public SnapshotCompareController(PersonService people, PersonSnapshotRepository snapshots, RollupService rollups,
            SheetService sheets) {
        this.people = people;
        this.snapshots = snapshots;
        this.rollups = rollups;
        this.sheets = sheets;
    }

    /** Per person in list order, the due-total of the SNAPSHOT written as the sheet's today left the
     *  from-date and of the one written as it left the to-date (the person's due-total now when no
     *  to-date is given); the last one written counts when a day was left more than once. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<Line> compare(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String sheet = sheetId.trim();
        Map<Long, String> fromDues = dueTotalsLeaving(sheet, from != null ? from : sheets.today(sheet));
        Map<Long, String> toDues = to == null ? rollups.dueTotals(sheet) : dueTotalsLeaving(sheet, to);
        return people.list(sheet).stream()
                .map(p -> new Line(p.getName(), fromDues.getOrDefault(p.getId(), ""),
                        toDues.getOrDefault(p.getId(), "")))
                .toList();
    }

    /** Each person's due-total in the last snapshot written for a day, by person id. */
    private Map<Long, String> dueTotalsLeaving(String sheet, LocalDate day) {
        Map<Long, String> dues = new HashMap<>();
        snapshots.findBySheetIdOrderByIdAsc(sheet).stream()
                .filter(s -> s.getDay().equals(day))
                .forEach(s -> dues.put(s.getPersonId(), s.getDueTotal()));
        return dues;
    }
}
