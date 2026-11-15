package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class RollupController {

    /** Figures added up over a sheet's descendants (or the sheet's own), the totals with two decimals. */
    public record ConsolidatedView(int people, String dueTotal, String overdueTotal) {
    }

    private final RollupService service;

    public RollupController(RollupService service) {
        this.service = service;
    }

    /** The figures of the sheet's descendants, each as it is now (even when the sheet is shown as of a date), added up. */
    @GetMapping("/descendants/figures")
    public ConsolidatedView figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.descendants(sheetId));
    }

    /** The sheet's own figures as it is now (even when the sheet is shown as of a date). */
    @GetMapping("/figures")
    public ConsolidatedView ownFigures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.own(sheetId));
    }

    private static ConsolidatedView view(RollupService.Consolidated c) {
        return new ConsolidatedView(c.people(), c.dueTotal().setScale(2).toPlainString(),
                c.overdueTotal().setScale(2).toPlainString());
    }
}
