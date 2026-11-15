package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** Figures added up over a sheet's descendants (or the sheet's own), the totals with two
     *  decimals: per grade the due-totals of the people on it (retired ones left out), and per
     *  status how many claims are in it (only grades and statuses with some), and each person's
     *  names and figures. */
    public record ConsolidatedView(int people, String dueTotal, String overdueTotal,
            Map<String, String> gradeDueTotals, Map<String, Integer> statusCounts, List<MemberView> members) {
    }

    /** A person's name and aliases, due-total (null: n/a) and overdue-total, with two decimals. */
    public record MemberView(List<String> names, String dueTotal, String overdueTotal) {
    }

    private final RollupService service;

    public RollupController(RollupService service) {
        this.service = service;
    }

    /** The figures of the sheet's descendants, each as it is now, or as it stood at the end of the
     *  date the sheet is shown as of (that date its today), added up. */
    @GetMapping("/descendants/figures")
    public ConsolidatedView figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return view(service.descendants(sheetId, asOf));
    }

    /** The sheet's own figures as it is now, or as it stood at the end of the date the sheet is
     *  shown as of (that date its today). */
    @GetMapping("/figures")
    public ConsolidatedView ownFigures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return view(service.own(sheetId, asOf));
    }

    private static ConsolidatedView view(RollupService.Consolidated c) {
        return new ConsolidatedView(c.people(), c.dueTotal().setScale(2).toPlainString(),
                c.overdueTotal().setScale(2).toPlainString(),
                c.gradeDueTotals().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().setScale(2).toPlainString(), (a, b) -> a, TreeMap::new)),
                c.statusCounts().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().name(),
                        Map.Entry::getValue, (a, b) -> a, TreeMap::new)),
                c.members().stream().map(m -> new MemberView(m.names(),
                        m.dueTotal() == null ? null : m.dueTotal().setScale(2).toPlainString(),
                        m.overdueTotal().setScale(2).toPlainString())).toList());
    }
}
