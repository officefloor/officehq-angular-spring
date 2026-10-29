package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to its own for the consolidated figures. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class RollupController {

    /** The descendants' figures added up: the number of people, and the due-totals and overdue-totals, two decimals. */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal) {
        static ConsolidatedView of(RollupService.Consolidated c) {
            return new ConsolidatedView(c.peopleCount(), c.dueTotal().toPlainString(), c.overdueTotal().toPlainString());
        }
    }

    private final RollupService service;

    public RollupController(RollupService service) {
        this.service = service;
    }

    @GetMapping("/figures")
    public ConsolidatedView figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return ConsolidatedView.of(service.descendants(sheetId));
    }

    /**
     * The sheet's own figures and its descendants' added up: as the store holds them now, or, given a date, each sheet
     * of the tree as it stood at the end of that day.
     */
    @GetMapping("/consolidated")
    public ConsolidatedView consolidated(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ConsolidatedView.of(asOf == null ? service.consolidated(sheetId) : service.consolidatedAsOf(sheetId, asOf));
    }
}
