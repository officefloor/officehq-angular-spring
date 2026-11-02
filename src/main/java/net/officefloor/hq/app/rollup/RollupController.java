package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
