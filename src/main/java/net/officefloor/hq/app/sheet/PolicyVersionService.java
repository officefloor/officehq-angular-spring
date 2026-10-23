package net.officefloor.hq.app.sheet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a sheet's policy versions with how many of its claims each is the policy version of. */
@Service
public class PolicyVersionService {

    /** A policy version with the count of the sheet's claims whose policy version it is. */
    public record Counted(PolicyVersion version, long claims) {
    }

    private final SheetService sheets;
    private final ClaimRepository claims;

    public PolicyVersionService(SheetService sheets, ClaimRepository claims) {
        this.sheets = sheets;
        this.claims = claims;
    }

    /**
     * The sheet's policy versions, oldest first, each with the count of its claims whose policy version
     * (the one in force on the claim's date) it is.
     */
    @Transactional
    public List<Counted> versions(String id) {
        Sheet sheet = sheets.load(id);
        Map<Integer, Long> counts = claims.findBySheetIdOrderByIdDesc(id).stream()
                .map(Claim::getDate)
                .filter(Objects::nonNull)
                .map(sheet::policyVersionOn)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return sheet.getPolicyVersions().stream()
                .map(v -> new Counted(v, counts.getOrDefault(v.getNumber(), 0L)))
                .toList();
    }
}
