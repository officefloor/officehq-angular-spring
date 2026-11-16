package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.format.annotation.DateTimeFormat;
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
public class RollupController {

    /** Figures added up over a sheet's descendants (or the sheet's own), the totals with two
     *  decimals: per grade the due-totals of the people on it (retired ones left out), and per
     *  status how many claims are in it (only grades and statuses with some), and each person's
     *  names and figures, and how many counting claims are overdue; and everyone's settled-total,
     *  open-total and cut total, each trimmed. */
    public record ConsolidatedView(int people, String dueTotal, String overdueTotal,
            Map<String, String> gradeDueTotals, Map<String, Integer> statusCounts, List<MemberView> members,
            int overdueCount, String settledTotal, String openTotal, String cutTotal) {
    }

    /** A person's name and aliases, due-total (null: n/a), overdue-total, settled-total, open-total
     *  and cut total, with two decimals. */
    public record MemberView(List<String> names, String dueTotal, String overdueTotal, String settledTotal,
            String openTotal, String cutTotal) {
    }

    /** A person of the sheet's tree: the sheet they are on, their name, their grade today and
     *  their due-total with two decimals (n/a: none). */
    public record TreePersonView(String sheet, String name, String grade, String dueTotal) {
    }

    private final RollupService service;
    private final SheetService sheets;

    public RollupController(RollupService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    /** The people of the sheet's whole tree (its ancestors and descendants): the sheet's first in
     *  list order, then the other sheets' by sheet id, each as it is now, or as it stood at the end
     *  of the date the sheet is shown as of. */
    @GetMapping("/tree/people")
    public List<TreePersonView> treePeople(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.treePeople(sheetId, sheets.tree(sheetId), asOf).stream()
                .map(p -> new TreePersonView(p.sheet(), p.name(), p.grade(), RollupService.text(p.dueTotal())))
                .toList();
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

    /** The sheet compared with another, one line per grade: the grade, the sheet's summary
     *  due-total for it and the other sheet's; no lines when the other sheet does not exist. */
    @GetMapping("/compare")
    public List<String> compare(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestParam @NotBlank @Size(max = 100) String other,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.compare(sheetId, other.trim(), asOf);
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
                        m.overdueTotal().setScale(2).toPlainString(), m.settledTotal().setScale(2).toPlainString(),
                        m.openTotal().setScale(2).toPlainString(), m.cutTotal().setScale(2).toPlainString()))
                        .toList(), c.overdueCount(),
                trimmed(c, RollupService.Member::settledTotal), trimmed(c, RollupService.Member::openTotal),
                trimmed(c, RollupService.Member::cutTotal));
    }

    /** One figure of everyone's, added up and trimmed, with two decimals. */
    private static String trimmed(RollupService.Consolidated c,
            java.util.function.Function<RollupService.Member, BigDecimal> figure) {
        return RollupService.trimmedSum(c.members().stream().map(figure).toList()).setScale(2).toPlainString();
    }
}
