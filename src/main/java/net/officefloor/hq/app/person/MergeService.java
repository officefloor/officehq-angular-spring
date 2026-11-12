package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimShareRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.plan.PlanRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges one person into another (a steward only; neither retired, not the same person). The merged person's claims
 * move to the other, keeping their numbers and dates, listed after the other's in the order they were added and
 * keeping any figure a statement bound; their name and aliases become the other's aliases; their patrons are added to
 * the other's and anyone naming them as patron names the other instead (never themselves, never twice); their pool
 * memberships move to the other (shares added where the other is a member already); their statements are counted
 * with the other's, whose last statement stays theirs (or, having none, becomes the merged person's last). Their
 * holds, watchers, plans and contact are dropped, and the merged person is kept aside, off the sheet.
 *
 * <p>The last merge into a person can be undone by the steward who made it, on the sheet's today it was made: the
 * merged person is back at the end of the list with the claims that came from them (as they are now, in their places
 * before) and their own aliases, which leave the other's; patron, pool and crew changes stay.
 */
@Service
public class MergeService {

    private final PersonRepository people;
    private final PersonAliasRepository aliases;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolRepository pools;
    private final CrewRepository crews;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;
    private final ClaimShareRepository shares;
    private final PersonMergeRepository merges;
    private final PlanRepository plans;

    public MergeService(PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, StatementRepository statements,
            PoolRepository pools, PeriodRepository periods, RoleService roles, SheetService sheets,
            RollupService rollup, Audit audit, CrewRepository crews,
            ClaimShareRepository shares, PersonMergeRepository merges, PlanRepository plans) {
        this.merges = merges;
        this.plans = plans;
        this.shares = shares;
        this.crews = crews;
        this.people = people;
        this.aliases = aliases;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.pools = pools;
        this.periods = periods;
        this.roles = roles;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Merge the person into the other; the other, as it now stands. */
    @Transactional
    public Person merge(String sheetId, String user, Long id, Long intoId) {
        Person from = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        Person into = people.findForUpdate(intoId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, intoId));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PersonMergeRefusedException("Only a steward can merge people");
        }
        if (from.getId().equals(into.getId())) {
            throw new PersonMergeRefusedException(from.getName() + " cannot be merged into themselves");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new PersonMergeRefusedException("A retired person cannot be merged");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        PersonMerge record = new PersonMerge(sheetId, from.getId(), into.getId(), user, sheets.today(sheetId));

        // The claims, listed after the other's in the order they were added, each keeping any figure a statement bound.
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId))
                .toList();
        for (Claim claim : theirs) {
            record.getClaims().add(new PersonMerge.MovedClaim(claim.getId(), claim.getListed()));
            claim.setPersonId(into.getId());
            claim.setListed(sheets.nextListed(sheetId));
        }
        claims.saveAll(theirs);

        // The shares they hold become the other's, added to any the other holds of the same claim; a share of a claim
        // that is now the other's own is dropped.
        List<Long> intoClaims = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, into.getId()).stream()
                .map(Claim::getId).toList();
        Map<Long, ClaimShare> intoHolds = new HashMap<>();
        for (ClaimShare share : shares.findByPersonIdOrderByIdAsc(into.getId())) {
            if (intoClaims.contains(share.getClaimId())) {
                shares.delete(share);
            } else {
                intoHolds.put(share.getClaimId(), share);
            }
        }
        for (ClaimShare share : shares.findByPersonIdOrderByIdAsc(from.getId())) {
            ClaimShare held = intoHolds.get(share.getClaimId());
            if (intoClaims.contains(share.getClaimId())) {
                shares.delete(share);
            } else if (held != null) {
                held.setShare(held.getShare().add(share.getShare()));
                shares.delete(share);
            } else {
                share.setPersonId(into.getId());
            }
        }
        shares.flush();

        // The name and aliases become the other's aliases; the name is free once the person is removed.
        List<PersonAlias> theirAliases = aliases.findByPersonId(from.getId());
        theirAliases.forEach(a -> a.setPersonId(into.getId()));
        theirAliases.forEach(a -> record.getAliasIds().add(a.getId()));
        aliases.saveAll(theirAliases);

        // Patrons: the merged person's are added to the other's, and those naming them name the other instead.
        List<PersonPatron> all = patrons.findBySheetIdOrderByIdAsc(sheetId);
        Set<Long> intoPatrons = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (PersonPatron p : all) {
            if (p.getPersonId().equals(into.getId())) {
                intoPatrons.add(p.getPatronId());
            }
            if (!p.getPersonId().equals(from.getId()) && !p.getPatronId().equals(from.getId())) {
                pairs.add(p.getPersonId() + ":" + p.getPatronId());
            }
        }
        List<PersonPatron> dropped = new ArrayList<>();
        List<PersonPatron> moved = new ArrayList<>();
        for (PersonPatron p : all) {
            if (!p.getPersonId().equals(from.getId()) && !p.getPatronId().equals(from.getId())) {
                continue;
            }
            Long person = p.getPersonId().equals(from.getId()) ? into.getId() : p.getPersonId();
            Long patron = p.getPatronId().equals(from.getId()) ? into.getId() : p.getPatronId();
            if (person.equals(patron) || !pairs.add(person + ":" + patron)) {
                dropped.add(p);
            } else {
                p.setPersonId(person);
                p.setPatronId(patron);
                moved.add(p);
            }
        }
        patrons.deleteAll(dropped);
        patrons.flush();
        patrons.saveAll(moved);

        // Pool memberships move, shares added where the other is a member already.
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            PoolMember membership = pool.getMembers().stream()
                    .filter(m -> from.getId().equals(m.getPersonId())).findFirst().orElse(null);
            if (membership == null) {
                continue;
            }
            PoolMember existing = pool.getMembers().stream()
                    .filter(m -> into.getId().equals(m.getPersonId())).findFirst().orElse(null);
            if (existing != null) {
                existing.setShare(existing.getShare().add(membership.getShare()));
                pool.getMembers().remove(membership);
            } else {
                membership.setPersonId(into.getId());
            }
            pools.saveAndFlush(pool);
        }

        // Crews follow: a crew the person leads is led by the other, and a membership moves, dropped where the other
        // already leads or is in the crew.
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            boolean changed = false;
            if (from.getId().equals(crew.getLeadId())) {
                crew.setLeadId(into.getId());
                crew.getMembers().removeIf(m -> into.getId().equals(m.getPersonId()));
                changed = true;
            }
            var membership = crew.getMembers().stream()
                    .filter(m -> from.getId().equals(m.getPersonId())).findFirst().orElse(null);
            if (membership != null) {
                if (crew.includes(into.getId())) {
                    crew.getMembers().remove(membership);
                } else {
                    membership.setPersonId(into.getId());
                }
                changed = true;
            }
            if (changed) {
                crews.saveAndFlush(crew);
            }
        }

        // A closed period's fixed figures follow the claims.
        for (Period period : periods.findBySheetIdOrderByFromDateAscIdAsc(sheetId)) {
            if (period.getPersonTotals().containsKey(from.getId())
                    || period.getPersonCarried().containsKey(from.getId())
                    || period.getPersonOpenings().containsKey(from.getId())) {
                period.mergePersonTotal(from.getId(), into.getId());
                periods.save(period);
            }
        }

        // Statements are counted with the other's (whose last stays theirs, or with none becomes the merged one's);
        // watchers, plans, holds and contact go with the person, who is kept aside.
        statements.follow(from, into);
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        claims.flush();
        plans.deleteByPersonId(from.getId());
        from.mergeAway();
        people.saveAndFlush(from);
        record.setNameAliasId(aliases.saveAndFlush(new PersonAlias(sheetId, into.getId(), from.getName())).getId());
        merges.save(record);

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user
                + " from=" + from.getName() + " into=" + into.getName());
        rollup.recordChanges(sheetId, user, before);
        return into;
    }

    /** Undo the last merge into the person; the person merged, back on the sheet. */
    @Transactional
    public Person undo(String sheetId, String user, Long intoId) {
        Person into = people.findForUpdate(intoId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, intoId));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PersonMergeRefusedException("Only a steward can undo a merge");
        }
        PersonMerge merge = merges.findFirstBySheetIdAndIntoIdOrderByIdDesc(sheetId, into.getId())
                .orElseThrow(() -> new PersonMergeRefusedException("No one was merged into " + into.getName()));
        if (!merge.getMergedBy().equals(user)) {
            throw new PersonMergeRefusedException("Only " + merge.getMergedBy() + " can undo that merge");
        }
        if (!merge.getMergedOn().equals(sheets.today(sheetId))) {
            throw new PersonMergeRefusedException("A merge can only be undone on the day it was made");
        }
        String name = people.findMergedAwayName(merge.getFromId(), sheetId)
                .orElseThrow(() -> new PersonMergeRefusedException("The merged person is no longer kept"));
        String key = Person.key(name);
        if (people.existsBySheetIdAndNameKey(sheetId, key)) {
            throw new PersonMergeRefusedException("Someone on the sheet is named " + name + " now");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);

        // The claims that came from them and are still the other's go back, to their places before.
        for (PersonMerge.MovedClaim moved : merge.getClaims()) {
            claims.findById(moved.claimId())
                    .filter(c -> sheetId.equals(c.getSheetId()) && into.getId().equals(c.getPersonId()))
                    .ifPresent(c -> {
                        c.setPersonId(merge.getFromId());
                        c.setListed(moved.listed());
                        claims.save(c);
                    });
        }
        claims.flush();

        // Their own aliases leave the other's, and the alias their name became is removed.
        if (merge.getNameAliasId() != null) {
            aliases.findById(merge.getNameAliasId()).ifPresent(aliases::delete);
        }
        for (PersonAlias alias : aliases.findAllById(merge.getAliasIds())) {
            if (into.getId().equals(alias.getPersonId())) {
                alias.setPersonId(merge.getFromId());
            }
        }
        aliases.flush();
        if (aliases.existsBySheetIdInAndAliasKeyAndPersonIdNot(List.of(sheetId), key, merge.getFromId())) {
            throw new PersonMergeRefusedException("Someone on the sheet goes by " + name + " now");
        }

        merges.delete(merge);
        merges.flush();
        people.bringBack(merge.getFromId(), sheetId, key, people.maxPosition(sheetId) + 1);
        Person restored = people.findByIdAndSheetId(merge.getFromId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, merge.getFromId()));

        audit.recordOnCommit("PERSON_MERGE_UNDONE sheet=" + sheetId + " by=" + user
                + " name=" + restored.getName() + " from=" + into.getName());
        rollup.recordChanges(sheetId, user, before);
        return restored;
    }
}
