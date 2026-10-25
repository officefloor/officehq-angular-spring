package net.officefloor.hq.app.person;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A steward's merge of one person into another on the same sheet. The merged person's claims move to the
 * other (numbers and dates kept, listed after the other's claims in the order they were added, each
 * keeping any figure a statement bound), and so do their statements (see {@link #carryStatements}); their name and aliases become aliases of the other; their
 * patrons are added to the other's (not the other themselves, no repeats), and anyone naming them as
 * patron names the other instead (no repeats); their pool memberships move to the other (shares added
 * where the other is already a member), and so do their crews (see {@link CrewService#personMerged}), and any due-total a closed period fixed for them is added to
 * the other's. Their holds, watchers and contact are dropped, and they leave the sheet. The merge is kept
 * (see {@link PersonMerge}) so the steward who made it can {@link #undo} it the same sheet's today.
 */
@Service
public class PersonMergeService {

    /** How the merged person is kept in their merge: their fields as JSON. */
    private static final JsonMapper STATE = JsonMapper.builder()
            .changeDefaultVisibility(v -> v.withFieldVisibility(Visibility.ANY)
                    .withGetterVisibility(Visibility.NONE)
                    .withIsGetterVisibility(Visibility.NONE)
                    .withSetterVisibility(Visibility.NONE))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final PersonRepository people;
    private final PersonMergeRepository merges;
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

    public PersonMergeService(PersonRepository people, PersonMergeRepository merges, PersonPatronRepository patrons, PersonWatchRepository watches,
            ClaimRepository claims, StatementRepository statements, SheetPoolMemberRepository poolMembers, ClaimShareRepository claimShares,
            CrewService crews, PeriodRepository periods, SettingsService settings, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.merges = merges;
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
        String state = STATE.writeValueAsString(from);

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
        merges.saveAndFlush(new PersonMerge(sheetId, into.getId(), from.getId(), from.getName(), user,
                sheets.today(sheetId), state, moved.stream().map(Claim::getId).toList(), from.getAliases()));

        audit.change("PERSON_MERGED", sheetId, user, "from=" + from.getName(), "into=" + into.getName());
        rollup.recordSince(sheetId, user, before);
        return into;
    }

    /**
     * Undoes the last merge into the person {@code intoId}, returning the person merged away, back under
     * their own id at the end of the list: the claims that came from them go back to them as they are now
     * (listed in the order they were), and so do their own aliases the other still has, which leave the
     * other's, as does their name; they have every grade, terms, tags, flag and approver they had. Patron,
     * pool, crew, share, statement and period changes the merge made stay. Refused when the person has no
     * merge to undo, unless the user is a steward and the one who made it, and it is still the sheet's
     * today it was made on, and when the merged person's name is taken again.
     */
    @Transactional
    public Person undo(String sheetId, String user, long intoId) {
        Person into = people.findByIdAndSheetId(intoId, sheetId).orElseThrow(PersonNotFoundException::new);
        PersonMerge merge = merges.findFirstBySheetIdAndIntoIdOrderByIdDesc(sheetId, into.getId())
                .filter(m -> !m.isUndone()).orElseThrow(PersonMergeUndoException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || !merge.getMergedBy().equals(user)
                || !merge.getMergedOn().equals(sheets.today(sheetId)) || people.existsById(merge.getFromId())) {
            throw new PersonMergeUndoException();
        }
        Person merged = STATE.readValue(merge.getPersonState(), Person.class);
        RollupService.Snapshot before = rollup.snapshot(sheetId);

        into.removeAlias(merged.getName());
        List<String> own = merge.getAliases().stream().filter(into::removeAlias).toList();
        people.flush();
        if (people.findBySheetIdAndNameOrAlias(sheetId, merged.getName()).isPresent()) {
            throw new PersonMergeUndoException();
        }
        merges.restorePerson(merge.getFromId(), sheetId, merged.getName(), Person.keyOf(merged.getName()),
                people.maxPosition(sheetId) + 1);
        Person restored = people.findById(merge.getFromId()).orElseThrow(PersonNotFoundException::new);
        restored.restoreFrom(merged, own);
        people.flush();

        List<Claim> back = claims.findAllById(merge.getClaimIds()).stream()
                .filter(c -> c.getSheetId().equals(sheetId) && c.getPersonId().equals(into.getId()))
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId))
                .toList();
        for (Claim claim : back) {
            claim.setPersonId(restored.getId());
            claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        }
        claims.flush();
        merge.undo();
        merges.flush();

        audit.change("PERSON_MERGE_UNDONE", sheetId, user, "name=" + restored.getName(), "from=" + into.getName());
        rollup.recordSince(sheetId, user, before);
        return restored;
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
