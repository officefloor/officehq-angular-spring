package net.officefloor.hq.app.person;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.patron.Patron;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.plan.PlanRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.statement.StatementRepository;
import net.officefloor.hq.app.watch.WatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Merging one person on a sheet into another. */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PatronRepository patrons;
    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final StatementRepository statements;
    private final WatchRepository watches;
    private final PeriodRepository periods;
    private final PlanRepository plans;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, ClaimRepository claims, PatronRepository patrons,
            PoolRepository pools, PoolMemberRepository members, StatementRepository statements,
            WatchRepository watches, PeriodRepository periods, PlanRepository plans, RoleService roles, Rollup rollup,
            Audit audit) {
        this.people = people;
        this.claims = claims;
        this.patrons = patrons;
        this.pools = pools;
        this.members = members;
        this.statements = statements;
        this.watches = watches;
        this.periods = periods;
        this.plans = plans;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Merge a person into another and return the other as they now stand. The merged person's
     *  claims move to the other (numbers, dates and statuses kept, listed after the other's in the
     *  order they were added, each losing any figure a statement bound); their name and aliases
     *  become aliases of the other; their patrons are added to the other's (not the other
     *  themselves, no repeats), anyone naming them as patron names the other instead (no repeats);
     *  their plans and pool memberships move to the other (shares added where the other is already a member)
     *  and their fixed due-totals of closed periods fold into the other's. Their statements, holds,
     *  watchers and contact are dropped, and they leave the sheet. Refused unless the user is a
     *  steward, and for the same person, or when either of them is retired. */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may merge people");
        }
        if (personId == intoId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be merged into themselves");
        }
        Person from = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Person into = people.lockByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        return rollup.tracking(sheetId, user, () -> {
            audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + from.getName()
                    + " into=" + into.getName());
            moveClaims(sheetId, from, into);
            movePatrons(sheetId, from, into);
            movePoolMemberships(sheetId, from, into);
            plans.findByPersonIdOrderByIdAsc(from.getId()).forEach(plan -> plan.setPersonId(into.getId()));
            plans.flush();
            periods.findBySheetIdOrderByFromDateAsc(sheetId)
                    .forEach(period -> period.mergePersonTotal(from.getId(), into.getId()));
            statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
            watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
            people.flush();
            Set<String> names = new HashSet<>(from.getAliases());
            names.add(from.getName());
            people.delete(from);
            people.flush();
            names.forEach(into::addAlias);
            return people.saveAndFlush(into);
        });
    }

    private void moveClaims(String sheetId, Person from, Person into) {
        claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparing(Claim::getId))
                .forEach(claim -> claim.moveTo(into.getId(), claims.nextListed()));
        claims.flush();
    }

    private void movePatrons(String sheetId, Person from, Person into) {
        List<Patron> all = patrons.findBySheetIdOrderByIdAsc(sheetId);
        Set<String> named = new HashSet<>();
        for (Patron p : all) {
            if (!p.getPersonId().equals(from.getId()) && !p.getPatronId().equals(from.getId())) {
                named.add(p.getPersonId() + ":" + p.getPatronId());
            }
        }
        // Anyone naming the merged person names the other instead, unless that is themselves or a repeat.
        for (Patron p : all) {
            if (p.getPatronId().equals(from.getId()) && !p.getPersonId().equals(from.getId())) {
                if (p.getPersonId().equals(into.getId()) || !named.add(p.getPersonId() + ":" + into.getId())) {
                    patrons.delete(p);
                } else {
                    p.setPatronId(into.getId());
                }
            }
        }
        // The merged person's own patrons are added to the other's.
        for (Patron p : all) {
            if (p.getPersonId().equals(from.getId())) {
                patrons.delete(p);
                if (!p.getPatronId().equals(into.getId()) && named.add(into.getId() + ":" + p.getPatronId())) {
                    patrons.save(new Patron(sheetId, into.getId(), p.getPatronId(), p.getNamedOn()));
                }
            }
        }
        patrons.flush();
    }

    private void movePoolMemberships(String sheetId, Person from, Person into) {
        List<Long> poolIds = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(p -> p.getId()).toList();
        if (poolIds.isEmpty()) {
            return;
        }
        List<PoolMember> all = members.findByPoolIdInOrderByIdAsc(poolIds);
        for (PoolMember m : all) {
            if (!from.getId().equals(m.getPersonId())) {
                continue;
            }
            all.stream()
                    .filter(o -> o.getPoolId().equals(m.getPoolId()) && into.getId().equals(o.getPersonId()))
                    .findFirst()
                    .ifPresentOrElse(o -> {
                        o.addShare(m.getShare());
                        members.delete(m);
                    }, () -> m.setPersonId(into.getId()));
        }
        members.flush();
    }
}
