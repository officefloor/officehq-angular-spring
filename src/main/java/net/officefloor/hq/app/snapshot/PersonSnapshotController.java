package net.officefloor.hq.app.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public PersonSnapshotController(PersonSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /** The sheet's snapshot lines, oldest first. */
    @GetMapping
    public List<SnapshotView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return snapshots.list(sheetId, asOf).stream().map(SnapshotView::of).toList();
    }
}
