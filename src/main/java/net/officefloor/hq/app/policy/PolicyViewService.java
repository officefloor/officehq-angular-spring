package net.officefloor.hq.app.policy;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.setting.PolicyVersion;
import net.officefloor.hq.app.setting.PolicyVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists a sheet's policy versions with what each changed and how many claims it is the policy of. */
@Service
public class PolicyViewService {

    /** A policy version: its number, its from-date (yyyy-mm-dd), the key and value it changed, and how
     *  many of the sheet's claims (DELETED ones left out) it is the policy version of. */
    public record Line(int number, String from, String key, String value, int claims) {
    }

    private final PolicyVersionRepository versions;
    private final ClaimRepository claims;

    public PolicyViewService(PolicyVersionRepository versions, ClaimRepository claims) {
        this.versions = versions;
        this.claims = claims;
    }

    /** The sheet's policy versions, oldest first. A claim's policy version is the number of versions in
     *  force on its date (one with no from-date is in force always). */
    @Transactional(readOnly = true)
    public List<Line> lines(String sheetId) {
        List<PolicyVersion> all = versions.findBySheetIdOrderByNumberAsc(sheetId);
        int[] counts = new int[all.size() + 1];
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            if (claim.getStatus() != ClaimStatus.DELETED) {
                LocalDate date = claim.getDate();
                counts[(int) all.stream().filter(v -> v.inForceOn(date)).count()]++;
            }
        }
        return all.stream().map(v -> new Line(v.getNumber(), v.getFrom() != null ? v.getFrom().toString() : "start",
                v.getKey() != null ? v.getKey() : "", v.getValue() != null ? v.getValue() : "",
                counts[v.getNumber()])).toList();
    }
}
