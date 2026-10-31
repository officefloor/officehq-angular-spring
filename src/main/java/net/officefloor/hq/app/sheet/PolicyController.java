package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.ClaimRepository;
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

    private final PolicyVersionRepository versions;
    private final SheetService sheets;
    private final ClaimRepository claims;

    public PolicyController(PolicyVersionRepository versions, SheetService sheets, ClaimRepository claims) {
        this.versions = versions;
        this.sheets = sheets;
        this.claims = claims;
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
}
