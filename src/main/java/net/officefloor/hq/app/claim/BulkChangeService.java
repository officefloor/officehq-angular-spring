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
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens or settles selected claims together. Each is changed on its own, as its own button would,
 *  so one that cannot be changed is left as it is and the others still change. */
@Service
public class BulkChangeService {

    /** What a bulk change did: the claims it changed, in the order it changed them, and how many it refused. */
    public record Result(List<Claim> changed, int refused) {
    }

    private final ClaimService service;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SettingService settings;

    public BulkChangeService(ClaimService service, ClaimRepository claims, PersonRepository people,
            SettingService settings) {
        this.service = service;
        this.claims = claims;
        this.people = people;
        this.settings = settings;
    }

    /** Open the claims of these ids in list order: people in the order they were added, each person's
     *  claims newest first. So their numbers follow that order. An id not of the sheet is refused. */
    public Result open(String sheetId, String user, Collection<Long> ids) {
        return change(sheetId, user, ids, ClaimStatus.OPEN);
    }

    /** Settle the claims of these ids in list order, each by the rules of settling one: a claim left
     *  PENDING counts as settled, one that cannot be settled is refused. An id not of the sheet is refused. */
    public Result settle(String sheetId, String user, Collection<Long> ids) {
        return change(sheetId, user, ids, ClaimStatus.SETTLED);
    }

    private Result change(String sheetId, String user, Collection<Long> ids, ClaimStatus next) {
        Set<Long> selected = new LinkedHashSet<>(ids);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) {
            return new Result(List.of(), selected.size()); // a viewer changes nothing: each is refused
        }
        List<Long> ordered = inListOrder(sheetId, selected);
        List<Claim> changed = new ArrayList<>();
        int refused = selected.size() - ordered.size();
        for (long id : ordered) {
            try {
                changed.add(service.changeStatus(sheetId, user, id, next));
            } catch (ResponseStatusException notChanged) {
                refused++;
            }
        }
        return new Result(changed, refused);
    }

    private List<Long> inListOrder(String sheetId, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) personOrder.put(listed.get(i).getId(), i);
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }
}
