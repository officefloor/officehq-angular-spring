package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.SettingsService;
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
    private final SettingsService settings;

    public ClaimBulkService(ClaimService claims, ClaimRepository repository, PersonRepository people,
            SettingsService settings) {
        this.claims = claims;
        this.repository = repository;
        this.people = people;
        this.settings = settings;
    }

    /**
     * Opens each of the claims that can be opened, in list order (the order of people, newest claim first within a
     * person), so they are numbered in that order. Each is opened in its own transaction: one that is refused, or is
     * not a claim of the sheet, is left as it is and counted as refused.
     */
    public Result open(String by, String sheetId, Set<Long> ids) {
        return each(by, sheetId, ids, id -> claims.changeStatus(by, sheetId, id, ClaimStatus.OPEN));
    }

    /**
     * Settles each of the claims that can be settled, in list order, by the rules of settling one claim: one the
     * clerk limit makes PENDING counts as settled. Each is settled in its own transaction: one that is refused, or is
     * not a claim of the sheet, is left as it is and counted as refused.
     */
    public Result settle(String by, String sheetId, Set<Long> ids) {
        return each(by, sheetId, ids, id -> claims.changeStatus(by, sheetId, id, ClaimStatus.SETTLED));
    }

    /**
     * Applies the change to each of the sheet's claims among the ids in list order, counting the refused ones. A
     * viewer, or anyone on an archived sheet, changes none: every one is refused.
     */
    private Result each(String by, String sheetId, Set<Long> ids, Function<Long, Claim> change) {
        if (settings.isReadOnly(sheetId, by)) {
            return new Result(List.of(), ids.size());
        }
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
        List<Claim> changed = new ArrayList<>();
        int refused = ids.size() - ordered.size();
        for (Long id : ordered) {
            try {
                changed.add(change.apply(id));
            } catch (ResponseStatusException e) {
                refused++;
            }
        }
        return new Result(changed, refused);
    }
}
