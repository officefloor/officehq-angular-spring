package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opens a selection of claims together, numbered in list order: the people in the order they were
 * added, each person's newest claim first. Each claim is opened on its own, so one that cannot be
 * opened is refused and left as it is while the rest go through.
 */
@Service
public class BulkOpenService {

    public record Result(int opened, int refused) {
    }

    private final ClaimService claims;
    private final ClaimRepository claimRows;
    private final PersonRepository people;

    public BulkOpenService(ClaimService claims, ClaimRepository claimRows, PersonRepository people) {
        this.claims = claims;
        this.claimRows = claimRows;
        this.people = people;
    }

    public Result open(String sheetId, String user, Set<Long> claimIds) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            personOrder.put(listed.get(i).getId(), i);
        }
        List<Claim> selected = claimRows.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> claimIds.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getId, Comparator.reverseOrder()))
                .toList();
        int opened = 0;
        for (Claim c : selected) {
            try {
                claims.move(sheetId, user, c.getId(), ClaimStatus.OPEN);
                opened++;
            } catch (ResponseStatusException | ClosedPeriodRefused refused) {
                // refused: left as it is
            }
        }
        return new Result(opened, claimIds.size() - opened);
    }
}
