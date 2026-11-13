package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.history.AsOf;
import net.officefloor.hq.app.history.Preview;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to the sheet's own for its consolidated
 *  figures. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The descendants' people count, and their due- and overdue-totals (two decimals), added up; then the
     *  sheet's own, for a screen showing the sheet as of a date to consolidate with. Each sheet as it stood at the
     *  end of that date, with it as the sheet's today, when the screen views the sheet as of one. By grade the
     *  due-totals of the people on it (retired people left out), and by status the number of claims in it, the
     *  descendants' added up, then the sheet's own; a grade or status with none left out. Then each descendant's
     *  people, and the sheet's own, with their names and aliases and their figures, for the screen to trim and
     *  count over the whole tree. Then the descendants' overdue claims that count, counted, for a steward's dashboard. */
    public record ConsolidatedView(int peopleCount, String dueTotal, String overdueTotal, int ownPeopleCount,
            String ownDueTotal, String ownOverdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts, Map<String, String> ownGradeDueTotals, Map<String, Integer> ownStatusCounts,
            List<MemberView> people, List<MemberView> ownPeople, int overdueCount) {
    }

    /** A person's names (their name, then their aliases) and their due-total (two decimals; null for n/a),
     *  overdue-total, settled-total, open-total and cut total (two decimals). */
    public record MemberView(List<String> names, String dueTotal, String overdueTotal, String settledTotal,
            String openTotal, String cutTotal) {
    }

    private final Rollup rollup;

    public ConsolidatedController(Rollup rollup) {
        this.rollup = rollup;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ConsolidatedView figures(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestHeader(value = Preview.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate preview) {
        String sid = sheetId.trim();
        LocalDate previewed = asOf == null ? preview : null;
        Rollup.Consolidated c = rollup.descendants(sid, asOf, previewed);
        Rollup.Snapshot own = asOf != null ? rollup.take(sid, asOf)
                : previewed != null ? rollup.preview(sid, previewed) : rollup.take(sid);
        Rollup.Breakdown ownBreakdown = previewed != null ? rollup.breakdown(sid, own, previewed)
                : rollup.breakdown(sid, asOf, own);
        return new ConsolidatedView(c.peopleCount(), c.dueTotal().toPlainString(), c.overdueTotal().toPlainString(),
                own.people().size(), own.dueTotal().toPlainString(), own.overdueTotal().toPlainString(),
                shown(c.gradeDueTotals()), counted(c.statusCounts()),
                shown(ownBreakdown.gradeDueTotals()), counted(ownBreakdown.statusCounts()),
                viewed(c.people()), viewed(rollup.members(sid, asOf, own)), c.overdueCount());
    }

    private static List<MemberView> viewed(List<Rollup.Member> members) {
        return members.stream().map(m -> new MemberView(m.names(),
                m.dueTotal() == null ? null : m.dueTotal().toPlainString(), m.overdueTotal().toPlainString(),
                m.settledTotal().toPlainString(), m.openTotal().toPlainString(), m.cutTotal().toPlainString())).toList();
    }

    private static Map<String, String> shown(Map<String, BigDecimal> totals) {
        Map<String, String> shown = new LinkedHashMap<>();
        totals.forEach((k, v) -> shown.put(k, v.toPlainString()));
        return shown;
    }

    private static Map<String, Integer> counted(Map<ClaimStatus, Integer> counts) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        counts.forEach((k, v) -> counted.put(k.name(), v));
        return counted;
    }
}
