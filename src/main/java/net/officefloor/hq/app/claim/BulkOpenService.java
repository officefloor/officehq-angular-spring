package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens selected claims together. Each is opened on its own, as its open button would, so one that
 *  cannot be opened is left as it is and the others still open. */
@Service
public class BulkOpenService {

    /** What a bulk open did: the claims it opened, in the order it opened them, and how many it refused. */
    public record Result(List<Claim> opened, int refused) {
    }

    private final ClaimService service;
    private final ClaimRepository claims;
    private final PersonRepository people;

    public BulkOpenService(ClaimService service, ClaimRepository claims, PersonRepository people) {
        this.service = service;
        this.claims = claims;
        this.people = people;
    }

    /** Open the claims of these ids in list order: people in the order they were added, each person's
     *  claims newest first. So their numbers follow that order. An id not of the sheet is refused. */
    public Result open(String sheetId, String user, Collection<Long> ids) {
        Set<Long> selected = new LinkedHashSet<>(ids);
        List<Long> ordered = inListOrder(sheetId, selected);
        List<Claim> opened = new ArrayList<>();
        int refused = selected.size() - ordered.size();
        for (long id : ordered) {
            try {
                opened.add(service.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
            } catch (ResponseStatusException notOpened) {
                refused++;
            }
        }
        return new Result(opened, refused);
    }

    private List<Long> inListOrder(String sheetId, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) personOrder.put(listed.get(i).getId(), i);
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }
}
