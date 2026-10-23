package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class TreeSearchController {

    /** A person found as the screen shows them: their sheet, name, grade in force on their sheet's today
     * and due-total (two decimals, or n/a). */
    public record FoundView(String sheetId, String name, String grade, String dueTotal) {
    }

    private final TreeSearch search;

    public TreeSearchController(TreeSearch search) {
        this.search = search;
    }

    /** The people over the sheet's whole tree whose name or an alias contains the text, case ignored. */
    @GetMapping("/{id}/search")
    public List<FoundView> search(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return search.search(id, text).stream()
                .map(found -> new FoundView(found.sheetId(), found.name(), found.grade(), Rollups.shown(found.dueTotal())))
                .toList();
    }
}
