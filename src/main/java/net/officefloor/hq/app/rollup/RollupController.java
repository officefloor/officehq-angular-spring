package net.officefloor.hq.app.rollup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to its own for the consolidated figures. */
@RestController
@RequestMapping("/api/rollup")
public class RollupController {

    public record DescendantsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** The sheet's own figures as the store holds them (as of the date the request is answered as of), as consolidated figures count them. */
    public record OwnFigures(int peopleCount, String dueTotal, String overdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts, List<TreePerson> people) {
    }

    /** A person as the consolidated figures take them: their name and aliases, due-total (null for n/a) and overdue-total. */
    public record TreePerson(List<String> names, String dueTotal, String overdueTotal) {
    }

    public record DescendantsResponse(int peopleCount, String dueTotal, String overdueTotal,
            Map<String, String> gradeDueTotals, Map<String, Integer> statusCounts, OwnFigures own,
            List<TreePerson> people) {
    }

    private final RollupService service;

    public RollupController(RollupService service) {
        this.service = service;
    }

    // ends in /list: it only reads; the as-of view answers it with every sheet of the tree as it stood then
    @PostMapping("/descendants/list")
    public DescendantsResponse descendants(@Valid @RequestBody DescendantsRequest req) {
        String sheet = req.sheet().trim();
        RollupService.Consolidated figures = service.descendants(sheet);
        RollupService.Snapshot own = service.snapshot(sheet);
        return new DescendantsResponse(figures.peopleCount(), money(figures.dueTotal()),
                money(figures.overdueTotal()), money(figures.gradeDueTotals()), figures.statusCounts(),
                new OwnFigures(own.people().size(), money(own.dueTotal()), money(own.overdueTotal()),
                        money(own.gradeDueTotals()), service.statusCounts(sheet), people(service.treePeople(sheet))),
                people(figures.people()));
    }

    private static List<TreePerson> people(List<RollupService.TreePerson> people) {
        return people.stream().map(p -> new TreePerson(p.names(),
                p.dueTotal() == null ? null : money(p.dueTotal()), money(p.overdueTotal()))).toList();
    }

    private static Map<String, String> money(Map<String, BigDecimal> amounts) {
        Map<String, String> out = new LinkedHashMap<>();
        amounts.forEach((key, amount) -> out.put(key, money(amount)));
        return out;
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
