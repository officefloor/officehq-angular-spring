package net.officefloor.hq.app.search;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search the people of a sheet's tree by name or alias. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/search")
public class TreeSearchController {

    /** A person found: their sheet id, name, grade in force on their sheet's today and due-total (two decimals; n/a when they have none). */
    public record FoundView(String sheetId, String name, String grade, String dueTotal) {
    }

    private final TreeSearchService service;

    public TreeSearchController(TreeSearchService service) {
        this.service = service;
    }

    /** The people whose name or an alias contains the text, case ignored. */
    @GetMapping
    public List<FoundView> search(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return service.search(sheetId.trim(), text).stream()
                .map(f -> new FoundView(f.sheetId(), f.name(), f.grade(),
                        f.dueTotal() == null ? "n/a" : f.dueTotal().toPlainString()))
                .toList();
    }
}
