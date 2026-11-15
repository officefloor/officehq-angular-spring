package net.officefloor.hq.app.search;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
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
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /** The people of the sheet's tree whose name or an alias contains the text (ignoring case);
     *  blank text finds everyone. */
    @GetMapping("/search")
    public List<SearchService.Found> search(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @NotNull @Size(max = 200) String text) {
        return service.search(sheetId, text);
    }
}
