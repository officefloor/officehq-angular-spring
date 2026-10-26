package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Status changes made to several claims together, each one on its own. */
@Service
public class ClaimBulkService {

    /** The claims a bulk action changed, in the order it took them, and how many it refused. */
    public record Result(List<Claim> changed, int refused) {
    }

    private final ClaimService claims;
    private final ClaimRepository repository;
    private final PersonRepository people;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, PersonRepository people) {
        this.claims = claims;
        this.repository = repository;
        this.people = people;
    }

    /**
     * Opens each of the claims that can be opened, in list order (the order of people, newest claim first within a
     * person), so they are numbered in that order. Each is opened in its own transaction: one that is refused, or is
     * not a claim of the sheet, is left as it is and counted as refused.
     */
    public Result open(String by, String sheetId, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person p : people.findBySheetIdOrderByIdAsc(sheetId)) {
            personOrder.put(p.getId(), personOrder.size());
        }
        List<Long> ordered = repository.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder())
                        .thenComparing(Claim::getId, Comparator.reverseOrder()))
                .map(Claim::getId).toList();
        List<Claim> opened = new ArrayList<>();
        int refused = ids.size() - ordered.size();
        for (Long id : ordered) {
            try {
                opened.add(claims.changeStatus(by, sheetId, id, ClaimStatus.OPEN));
            } catch (ResponseStatusException e) {
                refused++;
            }
        }
        return new Result(opened, refused);
    }
}
