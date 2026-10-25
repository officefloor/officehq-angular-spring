package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens, settles or moves selected claims together. Each is changed on its own, as its own button would,
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
    private final SheetRepository sheets;

    public BulkChangeService(ClaimService service, ClaimRepository claims, PersonRepository people,
            SettingService settings, SheetRepository sheets) {
        this.service = service;
        this.claims = claims;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
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

    /** Move the claims of these ids to another person in list order, each by the rules of moving one;
     *  one that cannot be moved is refused. An id not of the sheet is refused. */
    public Result reassign(String sheetId, String user, Collection<Long> ids, long toPersonId) {
        return each(sheetId, user, ids, id -> service.reassign(sheetId, user, id, toPersonId, true));
    }

    private Result change(String sheetId, String user, Collection<Long> ids, ClaimStatus next) {
        return each(sheetId, user, ids, id -> service.changeStatus(sheetId, user, id, next));
    }

    private Result each(String sheetId, String user, Collection<Long> ids, LongFunction<Claim> change) {
        Set<Long> selected = new LinkedHashSet<>(ids);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) {
            return new Result(List.of(), selected.size()); // a viewer changes nothing: each is refused
        }
        if (sheets.isArchived(sheetId)) {
            return new Result(List.of(), selected.size()); // an archived sheet changes nothing: each is refused
        }
        List<Long> ordered = inListOrder(sheetId, selected);
        List<Claim> changed = new ArrayList<>();
        int refused = selected.size() - ordered.size();
        for (long id : ordered) {
            try {
                changed.add(change.apply(id));
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
