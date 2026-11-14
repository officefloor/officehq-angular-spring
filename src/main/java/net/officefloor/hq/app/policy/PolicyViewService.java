package net.officefloor.hq.app.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.PolicyVersion;
import net.officefloor.hq.app.setting.PolicyVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists a sheet's policy versions with what each changed and how many claims it is the policy of. */
@Service
public class PolicyViewService {

    /** A policy version: its number, its from-date (yyyy-mm-dd), the key and value it changed, and how
     *  many of the sheet's claims (DELETED ones left out) it is the policy version of, and the dues (as shown) of those of them that count added up
     *  (two decimals; one with no figure left out). */
    public record Line(int number, String from, String key, String value, int claims, String dueTotal) {
    }

    private final PolicyVersionRepository versions;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;

    public PolicyViewService(PolicyVersionRepository versions, ClaimRepository claims, ClaimService claimService,
            PersonRepository people) {
        this.versions = versions;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
    }

    /** The sheet's policy versions, oldest first. A claim's policy version is the number of versions in
     *  force on its date (one with no from-date is in force always). */
    @Transactional(readOnly = true)
    public List<Line> lines(String sheetId) {
        List<PolicyVersion> all = versions.findBySheetIdOrderByNumberAsc(sheetId);
        Map<Long, Person> byId = people.findEverBySheetId(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        int[] counts = new int[all.size() + 1];
        BigDecimal[] dues = new BigDecimal[all.size() + 1];
        Arrays.fill(dues, BigDecimal.ZERO.setScale(2));
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            if (claim.getStatus() != ClaimStatus.DELETED) {
                LocalDate date = claim.getDate();
                int number = (int) all.stream().filter(v -> v.inForceOn(date)).count();
                counts[number]++;
                Person person = byId.get(claim.getPersonId());
                if (claim.counts() && person != null) {
                    BigDecimal due = claimService.dueOf(sheetId, person, claim);
                    if (due != null) {
                        dues[number] = dues[number].add(due);
                    }
                }
            }
        }
        return all.stream().map(v -> new Line(v.getNumber(), v.getFrom() != null ? v.getFrom().toString() : "start",
                v.getKey() != null ? v.getKey() : "", v.getValue() != null ? v.getValue() : "",
                counts[v.getNumber()], dues[v.getNumber()].setScale(2, RoundingMode.HALF_EVEN).toPlainString()))
                .toList();
    }
}
