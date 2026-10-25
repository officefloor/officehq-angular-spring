package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimShareRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.pool.SheetPoolMember;
import net.officefloor.hq.app.pool.SheetPoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward's merge of one person into another on the same sheet. The merged person's claims move to the
 * other (numbers and dates kept, listed after the other's claims in the order they were added, each
 * keeping any figure a statement bound), and so do their statements (see {@link #carryStatements}); their name and aliases become aliases of the other; their
 * patrons are added to the other's (not the other themselves, no repeats), and anyone naming them as
 * patron names the other instead (no repeats); their pool memberships move to the other (shares added
 * where the other is already a member), and so do their crews (see {@link CrewService#personMerged}), and any due-total a closed period fixed for them is added to
 * the other's. Their holds, watchers and contact are dropped, and they leave the sheet.
 */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final SheetPoolMemberRepository poolMembers;
    private final ClaimShareRepository claimShares;
    private final CrewService crews;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PersonPatronRepository patrons, PersonWatchRepository watches,
            ClaimRepository claims, StatementRepository statements, SheetPoolMemberRepository poolMembers, ClaimShareRepository claimShares,
            CrewService crews, PeriodRepository periods, SettingsService settings, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.claimShares = claimShares;
        this.crews = crews;
        this.periods = periods;
        this.settings = settings;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Merges the person {@code personId} into {@code intoId}, returning the person merged into; refused
     * unless the user is a steward, for the person themselves, and when either is retired. */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        Person from = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Person into = people.findByIdAndSheetId(intoId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || from.getId().equals(into.getId())
                || from.isRetired() || into.isRetired()) {
            throw new PersonMergeException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);

        List<Claim> moved = new ArrayList<>(claims.lockBySheetIdAndPersonId(sheetId, from.getId()));
        Collections.reverse(moved);
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
            claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        }
        claims.flush();

        List<String> aliases = new ArrayList<>();
        aliases.add(from.getName());
        aliases.addAll(from.getAliases());

        for (PersonPatron named : patrons.findByPersonIdOrderByIdAsc(from.getId())) {
            Long patron = named.getPatronId();
            if (patron.equals(into.getId()) || patrons.existsByPersonIdAndPatronId(into.getId(), patron)) {
                patrons.delete(named);
            } else {
                named.setPersonId(into.getId());
            }
            patrons.flush();
        }
        for (PersonPatron naming : patrons.findByPatronIdOrderByIdAsc(from.getId())) {
            Long person = naming.getPersonId();
            if (person.equals(into.getId()) || patrons.existsByPersonIdAndPatronId(person, into.getId())) {
                patrons.delete(naming);
            } else {
                naming.setPatronId(into.getId());
            }
            patrons.flush();
        }

        for (SheetPoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            var already = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (already.isPresent()) {
                already.get().setShare(already.get().getShare().add(member.getShare()));
                poolMembers.delete(member);
            } else {
                member.setPersonId(into.getId());
            }
            poolMembers.flush();
        }

        // A share the merged person held passes to the person merged into, unless the claim is theirs or
        // they hold one of it already; a share they held of a claim now theirs is dropped.
        for (Claim claim : moved) {
            claimShares.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                    .filter(s -> s.getPersonId().equals(into.getId())).forEach(claimShares::delete);
        }
        claimShares.flush();
        for (ClaimShare held : claimShares.findByPersonIdOrderByIdAsc(from.getId())) {
            Long claimId = held.getClaimId();
            boolean own = claims.findById(claimId).map(c -> c.getPersonId().equals(into.getId())).orElse(true);
            if (own || claimShares.findByClaimIdOrderByIdAsc(claimId).stream()
                    .anyMatch(s -> s.getPersonId().equals(into.getId()))) {
                claimShares.delete(held);
            } else {
                held.setPersonId(into.getId());
            }
            claimShares.flush();
        }

        crews.personMerged(from.getId(), into.getId());

        periods.findBySheetIdOrderByFromAsc(sheetId).forEach(period -> period.mergePersonTotal(from.getId(), into.getId()));
        periods.flush();

        carryStatements(statements, sheetId, from.getId(), sheetId, into.getId());
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        statements.flush();
        watches.flush();

        people.delete(from);
        people.flush();
        aliases.forEach(into::addAlias);
        people.flush();

        audit.change("PERSON_MERGED", sheetId, user, "from=" + from.getName(), "into=" + into.getName());
        rollup.recordSince(sheetId, user, before);
        return into;
    }

    /**
     * Moves the statements of the person {@code fromId} on {@code fromSheetId} to the person {@code intoId}
     * on {@code intoSheetId}, keeping their order and their ids (so the claims they bound stay bound by
     * them). They are placed before the receiving person's own statements, whose last stays their last;
     * when the receiving person has none, the moved ones keep their places and their last becomes theirs.
     */
    static void carryStatements(StatementRepository statements, String fromSheetId, Long fromId,
            String intoSheetId, Long intoId) {
        List<Statement> moving = statements.inOrder(fromSheetId, fromId);
        List<Statement> own = statements.inOrder(intoSheetId, intoId);
        long first = own.isEmpty() ? 0 : own.get(0).getPosition();
        for (int i = 0; i < moving.size(); i++) {
            Statement statement = moving.get(i);
            long position = own.isEmpty() ? statement.getPosition() : first - moving.size() + i;
            statement.movedTo(intoSheetId, intoId, position);
        }
        statements.flush();
    }
}
