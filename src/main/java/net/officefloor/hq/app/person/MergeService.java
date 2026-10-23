package net.officefloor.hq.app.person;

import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.plan.Plan;
import net.officefloor.hq.app.plan.PlanRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merging a person into another on the sheet: a steward only. The merged person's claims move to the
 * other, numbers, dates and any figure a statement bound kept, listed after the other's own in the
 * order they were added. Their name and aliases become aliases of the other; their
 * patrons are added to the other's (not the other themselves, no repeats); anyone naming them as a
 * patron names the other instead (no repeats); their pool memberships move to the other, shares added
 * where the other is already a member; their plans become the other's. Their statements join the
 * other's: the other's count becomes their own plus theirs, and the other's last statement stays the
 * other's (or, the other having none, becomes theirs). Their holds, watchers and contact are dropped,
 * and they leave the sheet.
 */
@Service
public class MergeService {

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final StatementService statements;
    private final WatchRepository watches;
    private final PoolMemberRepository poolMembers;
    private final PlanRepository plans;
    private final RoleService roles;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;

    public MergeService(PersonRepository people, ClaimRepository claims, StatementService statements,
            WatchRepository watches, PoolMemberRepository poolMembers, PlanRepository plans, RoleService roles, SheetService sheets,
            Rollups rollups, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.statements = statements;
        this.watches = watches;
        this.poolMembers = poolMembers;
        this.plans = plans;
        this.roles = roles;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /**
     * Merge a person into another on the sheet: the person merged into, as the store holds them after.
     * Refused unless the user is a steward, for the person themselves, and when either is retired.
     */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        sheets.lock(sheetId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward merges people.");
        }
        Person from = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Person into = people.findByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (from.getId().equals(into.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves.");
        }
        if (from.getRetiredFrom() != null || into.getRetiredFrom() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        Rollups.Rollup before = rollups.take(sheetId);

        // Their claims, in the order they were added, move to the other, still bound as they were.
        List<Claim> moved = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, from.getId()).stream()
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : moved) {
            claim.reassign(into.getId());
        }
        claims.saveAll(moved);

        // Their name and aliases become the other's aliases; their patrons the other's.
        into.addAlias(from.getName());
        from.getAliases().forEach(into::addAlias);
        for (Long patronId : from.getPatronIds()) {
            if (!patronId.equals(into.getId())) {
                into.addPatron(patronId);
            }
        }
        // Anyone naming them as a patron names the other instead.
        for (Person other : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (other.getId().equals(from.getId()) || !other.removePatron(from.getId())) {
                continue;
            }
            if (!other.getId().equals(into.getId())) {
                other.addPatron(into.getId());
            }
            people.save(other);
        }

        // Their pool memberships move to the other, shares added where the other is already a member.
        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            PoolMember theirs = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId()).orElse(null);
            if (theirs != null) {
                theirs.addShare(member.getShare());
                poolMembers.save(theirs);
                poolMembers.delete(member);
            } else {
                member.moveTo(into.getId());
                poolMembers.save(member);
            }
        }

        // Their plans become the other's.
        for (Plan plan : plans.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId())) {
            plan.moveTo(into.getId());
            plans.save(plan);
        }

        // Their statements join the other's; their watchers are dropped, and they leave the sheet with
        // their holds and contact.
        statements.handOver(sheetId, from, into);
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
        String fromName = from.getName();
        people.delete(from);
        people.flush();
        Person saved = people.saveAndFlush(into);
        statements.reread(saved);

        // The moved claims are listed after the other's own, in the order they were added.
        moved.forEach(claim -> claims.relist(claim.getId()));

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user
                + " from=" + fromName + " into=" + saved.getName());
        rollups.audit(sheetId, user, before);
        return people.findByIdAndSheetId(intoId, sheetId).orElseThrow();
    }
}
