package net.officefloor.hq.app.claim;

import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;

/** Opens, settles, moves, re-dates or voids selected claims together. Each is changed on its own, as its own button would,
 *  so one that cannot be changed is left as it is and the others still change; a bulk settle is the
 *  exception: it settles all of them or none. */
@Service
public class BulkChangeService {

    /** What a bulk change did: the claims it changed, in the order it changed them, and how many it refused. */
    public record Result(List<Claim> changed, int refused) {
    }

    /** What a bulk settle did: as {@link Result}, and the number of the batch it made, or null when it
     *  settled none and so made no batch. */
    public record SettleResult(List<Claim> changed, int refused, Integer batch) {
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

    /** Settle the claims of these ids in list order, each by the rules of settling one, all or nothing:
     *  a claim left PENDING counts as settled, but if any one cannot be settled (or an id is not of the
     *  sheet) none is, every one counts as refused and nothing is written. */
    @Transactional
    public SettleResult settle(String sheetId, String user, Collection<Long> ids) {
        Result result = change(sheetId, user, ids, ClaimStatus.SETTLED);
        if (result.refused() > 0) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new SettleResult(List.of(), new LinkedHashSet<>(ids).size(), null);
        }
        Integer batch = result.changed().isEmpty() ? null : service.markSettleBatch(sheetId, user, result.changed());
        return new SettleResult(result.changed(), result.refused(), batch);
    }

    /** Move the claims of these ids to another person in list order, each by the rules of moving one;
     *  one that cannot be moved is refused. An id not of the sheet is refused. */
    public Result reassign(String sheetId, String user, Collection<Long> ids, long toPersonId) {
        return each(sheetId, user, ids, id -> service.reassign(sheetId, user, id, toPersonId, true));
    }

    /** Re-date the claims of these ids in list order, each by the rules of re-dating one; one that
     *  cannot be re-dated is refused. An id not of the sheet is refused. */
    public Result redate(String sheetId, String user, Collection<Long> ids, LocalDate date) {
        return each(sheetId, user, ids, id -> service.redate(sheetId, user, id, date));
    }

    /** Void the claims of these ids in list order, each by the rules of voiding one (with the reason,
     *  while the sheet has void reasons); one that cannot be voided is refused. An id not of the sheet is refused. */
    public Result voidSelected(String sheetId, String user, Collection<Long> ids, String reason) {
        return each(sheetId, user, ids, id -> service.voidClaim(sheetId, user, id, reason));
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
        List<Person> listed = people.findInListOrder(sheetId);
        for (int i = 0; i < listed.size(); i++) personOrder.put(listed.get(i).getId(), i);
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> ids.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }
}
