package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
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
 * holds, watchers and contact are dropped, and the merged person is removed.
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
    private final PeriodRepository periods;
    private final RoleService roles;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public MergeService(PersonRepository people, PersonAliasRepository aliases, PersonPatronRepository patrons,
            PersonWatchRepository watches, ClaimRepository claims, StatementRepository statements,
            PoolRepository pools, PeriodRepository periods, RoleService roles, SheetService sheets,
            RollupService rollup, Audit audit) {
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

        // The claims, listed after the other's in the order they were added, each keeping any figure a statement bound.
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId))
                .toList();
        for (Claim claim : theirs) {
            claim.setPersonId(into.getId());
            claim.setListed(sheets.nextListed(sheetId));
        }
        claims.saveAll(theirs);

        // The name and aliases become the other's aliases; the name is free once the person is removed.
        List<PersonAlias> theirAliases = aliases.findByPersonId(from.getId());
        theirAliases.forEach(a -> a.setPersonId(into.getId()));
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

        // A closed period's fixed figures follow the claims.
        for (Period period : periods.findBySheetIdOrderByFromDateAscIdAsc(sheetId)) {
            if (period.getPersonTotals().containsKey(from.getId())) {
                period.mergePersonTotal(from.getId(), into.getId());
                periods.save(period);
            }
        }

        // Statements are counted with the other's (whose last stays theirs, or with none becomes the merged one's);
        // watchers, holds and contact go with the person.
        statements.follow(from, into);
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        claims.flush();
        people.delete(from);
        people.flush();
        aliases.saveAndFlush(new PersonAlias(sheetId, into.getId(), from.getName()));

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user
                + " from=" + from.getName() + " into=" + into.getName());
        rollup.recordChanges(sheetId, user, before);
        return into;
    }
}
