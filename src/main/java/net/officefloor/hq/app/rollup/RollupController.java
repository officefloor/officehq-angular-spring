package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** The figures of the sheet's descendants added up, for the screen to consolidate with its own. */
    @GetMapping("/descendants")
    public Rollup.Descendants descendants(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return rollup.descendants(sheetId.trim());
    }

    /** The people on the sheet and the other sheets of its tree whose name or an alias contains the
     *  text, case ignored (see {@link Rollup#search}). */
    @GetMapping("/search")
    public List<Rollup.Found> search(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return rollup.search(sheetId.trim(), text);
    }
}
