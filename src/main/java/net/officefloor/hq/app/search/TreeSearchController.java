package net.officefloor.hq.app.search;

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
@Validated
@RequestMapping("/api/sheets/{sheetId}/search")
public class TreeSearchController {

    private final TreeSearchService service;

    public TreeSearchController(TreeSearchService service) {
        this.service = service;
    }

    /** The people on the sheet and the other sheets of its tree whose name or an alias contains the
     *  text, case ignored (blank finds everyone). */
    @GetMapping
    public List<TreeSearchService.Found> search(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        return service.search(sheetId.trim(), name);
    }
}
