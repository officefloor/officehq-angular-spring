package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merging a person into another on the same sheet. Only a steward may; merging a person into themselves, or
 * when either of them is retired, is refused. The merged person's claims move to the other (numbers and dates
 * kept, listed after the other's claims in the order they were added, each keeping any figure a statement
 * bound); their statements are carried to the other, who keeps their own last statement (or, having none,
 * takes the merged person's last) with the count of both; their name and aliases become the other's aliases; their patrons are added to the
 * other's (never the other themselves, no repeats); anyone naming them as patron names the other instead
 * (no repeats); their pool memberships move to the other, the shares added up (at most 100.00) where the
 * other is already a member. Their holds, watchers and contact are dropped with them.
 */
@Service
public class PersonMergeService {

    private static final BigDecimal FULL_SHARE = new BigDecimal("100.00");

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementService statements;
    private final PoolMemberRepository poolMembers;
    private final AccessService access;
    private final SheetRepository sheetRows;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementService statements, PoolMemberRepository poolMembers,
            AccessService access, SheetRepository sheetRows, Rollup rollup, ApplicationEventPublisher events) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.access = access;
        this.sheetRows = sheetRows;
        this.rollup = rollup;
        this.events = events;
    }

    /** Merge the person into the other; answers with the other as they are now. */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        sheetRows.lockById(sheetId);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge people");
        }
        if (personId == intoId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves");
        }
        Person from = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person into = people.findByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No such person"));
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        Rollup.Figures before = rollup.figures(sheetId);

        List<Claim> moving = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId));
        moving.sort(Comparator.comparing(Claim::getId));
        for (Claim claim : moving) {
            claim.setPersonId(intoId);
        }
        claims.flush();
        for (Claim claim : moving) {
            claims.relist(claim.getId());
        }

        into.addAlias(from.getName());
        from.getAliases().forEach(into::addAlias);

        mergePatrons(sheetId, personId, intoId);
        mergePools(personId, intoId);
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
        statements.carry(sheetId, personId, sheetId, intoId);
        watches.flush();
        people.delete(from);
        people.flush();

        events.publishEvent(new PersonMerged(sheetId, user, from.getName(), into.getName()));
        rollup.changed(sheetId, user, before);
        return into;
    }

    /** The merged person's patrons join the other's, and whoever named the merged person names the other. */
    private void mergePatrons(String sheetId, long personId, long intoId) {
        List<Patron> theirs = patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        List<Patron> naming = patrons.findBySheetIdAndPatronIdOrderByIdAsc(sheetId, personId);
        Set<Long> intoPatrons = patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, intoId).stream()
                .map(Patron::getPatronId).collect(Collectors.toCollection(LinkedHashSet::new));
        patrons.deleteAll(theirs);
        patrons.deleteAll(naming);
        patrons.flush();
        List<Patron> added = new ArrayList<>();
        for (Patron p : theirs) {
            long patronId = p.getPatronId();
            if (patronId != intoId && intoPatrons.add(patronId)) {
                added.add(new Patron(sheetId, intoId, patronId));
            }
        }
        for (Patron p : naming) {
            long namer = p.getPersonId();
            if (namer != intoId && !patrons.existsByPersonIdAndPatronId(namer, intoId)
                    && added.stream().noneMatch(a -> a.getPersonId() == namer)) {
                added.add(new Patron(sheetId, namer, intoId));
            }
        }
        patrons.saveAllAndFlush(added);
    }

    /** The merged person's pool memberships move to the other, adding shares where the other is a member. */
    private void mergePools(long personId, long intoId) {
        Map<Long, PoolMember> intoByPool = poolMembers.findByPersonId(intoId).stream()
                .collect(Collectors.toMap(PoolMember::getPoolId, Function.identity()));
        for (PoolMember member : poolMembers.findByPersonId(personId)) {
            PoolMember existing = intoByPool.get(member.getPoolId());
            if (existing == null) {
                member.setPersonId(intoId);
            } else {
                existing.setShare(existing.getShare().add(member.getShare()).min(FULL_SHARE));
                poolMembers.delete(member);
            }
        }
        poolMembers.flush();
    }
}
