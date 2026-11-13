package net.officefloor.hq.app.claim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;

/** Opens a selection of claims together. */
@Service
public class BulkOpenService {

    /** What a bulk open did: the claims it opened, in the order it opened them, and how many of the
     *  selected claims it refused. */
    public record Result(List<Claim> opened, int refused) {
    }

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final RollupService rollups;

    public BulkOpenService(ClaimRepository claims, PersonRepository people, ClaimService service,
            RollupService rollups) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.rollups = rollups;
    }

    /** Open each selected claim that can be opened, in list order (people in the order they were
     *  added, each person's claims newest first), so they are numbered in that order; each that
     *  cannot, or is not on the sheet, is refused and left as it is. Each claim is opened on its
     *  own, so a refused one writes nothing. */
    public Result open(String sheetId, String user, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < sheetPeople.size(); i++) {
            personOrder.put(sheetPeople.get(i).getId(), i);
        }
        List<Claim> selected = claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(claim -> ids.contains(claim.getId()))
                .sorted(Comparator.comparing((Claim claim) -> personOrder.getOrDefault(claim.getPersonId(), Integer.MAX_VALUE)))
                .toList();
        List<Claim> opened = new ArrayList<>();
        for (Claim claim : selected) {
            try {
                opened.add(rollups.track(sheetId, user, () -> service.changeStatus(sheetId, user,
                        claim.getPersonId(), claim.getId(), ClaimStatus.OPEN)));
            } catch (StatusChangeRefusedException | PeriodClosedException | NoSuchElementException refused) {
                // Left as it is: its change rolled back with its own transaction.
            }
        }
        return new Result(opened, ids.size() - opened.size());
    }
}
