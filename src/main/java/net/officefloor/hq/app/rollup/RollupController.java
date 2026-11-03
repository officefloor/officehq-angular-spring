package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class RollupController {

    private final Rollup rollup;

    public RollupController(Rollup rollup) {
        this.rollup = rollup;
    }

    /** The figures of the sheet's descendants added up, for the screen to consolidate with its own;
     *  as of a day when one is given, each as it stood at the end of it. */
    @GetMapping("/descendants")
    public Rollup.Descendants descendants(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return rollup.descendants(sheetId.trim(), asOf);
    }

    /** Another sheet's per-grade due-totals, for the screen to compare the sheet with (see
     *  {@link Rollup#comparison}). */
    @GetMapping("/compare")
    public Rollup.Comparison compare(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam @NotBlank @Size(max = 100) String other) {
        return rollup.comparison(other.trim());
    }

    /** The people on the sheet and the other sheets of its tree whose name or an alias contains the
     *  text, case ignored (see {@link Rollup#search}). */
    @GetMapping("/search")
    public List<Rollup.Found> search(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return rollup.search(sheetId.trim(), text);
    }

    /** Everyone on the sheet and the other sheets of its tree, for the tree export (see
     *  {@link Rollup#treePeople}). */
    @GetMapping("/tree-people")
    public List<Rollup.TreePerson> treePeople(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return rollup.treePeople(sheetId.trim());
    }
}
