package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A sheet's policy versions, oldest first: one line {@code <version> <from or start> <key>=<value> <claims>}
 * each, the value blank when saved blank, and the claims the count of the sheet's claims whose policy version
 * it is (the one in force on the claim's date, see {@link DatedSettings#policyVersion(java.time.LocalDate)}).
 * It records nothing.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}/policy-versions")
@Validated
public class PolicyController {

    /**
     * A policy version as exported: its number, its from-date (null: from the start), key and value (blank when
     * saved blank), the count of the sheet's claims whose policy version it is, and the sum of the due figures
     * of those of them that count in the live totals (see {@link Dues#counts}; 0.00 with none).
     */
    public record VersionRow(int version, String from, String key, String value, long claims, String dueTotal) {
    }

    private final PolicyVersionRepository versions;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final PersonService people;
    private final RateService rates;

    public PolicyController(PolicyVersionRepository versions, SheetService sheets, ClaimRepository claims,
            PersonService people, RateService rates) {
        this.versions = versions;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        DatedSettings settings = sheets.settings(sheet);
        Map<Integer, Long> counts = claims.findBySheetIdOrderByIdDesc(sheet).stream()
                .map(c -> settings.policyVersion(c.getDate()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return versions.findBySheetIdOrderByVersionAsc(sheet).stream()
                .map(v -> v.getVersion() + " " + (v.getFromDate() == null ? "start" : v.getFromDate().toString())
                        + " " + v.getKey() + "=" + (v.getValue() == null ? "" : v.getValue())
                        + " " + counts.getOrDefault(v.getVersion(), 0L))
                .toList();
    }


    /** The sheet's policy versions, oldest first, each with its claims and their due-total (see {@link VersionRow}). */
    @GetMapping("/export")
    @Transactional(readOnly = true)
    public List<VersionRow> export(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        RateTable table = rates.table(sheet);
        DatedSettings settings = table.settings();
        Map<Long, Person> byId = people.list(sheet).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Integer, List<Claim>> byVersion = claims.findBySheetIdOrderByIdDesc(sheet).stream()
                .collect(Collectors.groupingBy(c -> settings.policyVersion(c.getDate())));
        return versions.findBySheetIdOrderByVersionAsc(sheet).stream().map(v -> {
            List<Claim> theirs = byVersion.getOrDefault(v.getVersion(), List.of());
            BigDecimal due = theirs.stream().map(c -> {
                Person person = byId.get(c.getPersonId());
                return person == null || !Dues.counts(c, person.leaves(), table.today()) ? null
                        : Dues.figure(c, person::gradeOn, table);
            }).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new VersionRow(v.getVersion(), v.getFromDate() == null ? null : v.getFromDate().toString(),
                    v.getKey(), v.getValue() == null ? "" : v.getValue(), theirs.size(),
                    due.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }).toList();
    }
}
