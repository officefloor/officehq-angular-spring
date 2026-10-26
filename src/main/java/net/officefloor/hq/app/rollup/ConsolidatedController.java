package net.officefloor.hq.app.rollup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.asof.AsOf;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class ConsolidatedController {

    private final ConsolidatedService service;
    private final TreeSearchService search;

    public ConsolidatedController(ConsolidatedService service, TreeSearchService search) {
        this.service = service;
        this.search = search;
    }

    /** The figures of the sheet's descendants added up; the screen adds its own to consolidate them. Read
     *  from a screen set to an as-of date, every sheet of the tree as it stood at the end of that day. */
    @GetMapping("/{id}/descendants")
    public ConsolidatedService.Descendants descendants(@PathVariable @NotBlank @Size(max = 100) String id,
            HttpServletRequest request) {
        return service.descendantsOf(id.trim(), AsOf.of(request).orElse(null));
    }

    /** The people over the sheet's tree whose name or an alias contains the text, case ignored. */
    @GetMapping("/{id}/tree-search")
    public List<TreeSearchService.Found> treeSearch(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return search.search(id.trim(), text);
    }
}
