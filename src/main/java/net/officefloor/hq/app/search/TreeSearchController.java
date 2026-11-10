package net.officefloor.hq.app.search;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Searching the people of a sheet and every other sheet of its tree by name or alias. */
@RestController
@RequestMapping("/api/search")
public class TreeSearchController {

    public record SearchRequest(@NotBlank @Size(max = 100) String sheet, @NotNull @Size(max = 200) String text) {
    }

    public record SearchResponse(List<TreeSearchService.Found> people) {
    }

    private final TreeSearchService service;

    public TreeSearchController(TreeSearchService service) {
        this.service = service;
    }

    // ends in /list: it only reads; the as-of view answers it as the store holds the sheets now
    @PostMapping("/tree/list")
    public SearchResponse search(@Valid @RequestBody SearchRequest req) {
        return new SearchResponse(service.search(req.sheet().trim(), req.text()));
    }
}
