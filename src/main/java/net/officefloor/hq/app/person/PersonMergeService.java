package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.SheetPoolMember;
import net.officefloor.hq.app.pool.SheetPoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A steward's merge of one person into another on the same sheet. The merged person's claims move to the
 * other (numbers and dates kept, listed after the other's claims in the order they were added, each
 * losing any figure a statement bound); their name and aliases become aliases of the other; their
 * patrons are added to the other's (not the other themselves, no repeats), and anyone naming them as
 * patron names the other instead (no repeats); their pool memberships move to the other (shares added
 * where the other is already a member), and any due-total a closed period fixed for them is added to
 * the other's. Their statements, holds, watchers and contact are dropped, and they leave the sheet.
 */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final SheetPoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonMergeService(PersonRepository people, PersonPatronRepository patrons, PersonWatchRepository watches,
            ClaimRepository claims, StatementRepository statements, SheetPoolMemberRepository poolMembers,
            PeriodRepository periods, SettingsService settings, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
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
            claim.unbind();
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

        periods.findBySheetIdOrderByFromAsc(sheetId).forEach(period -> period.mergePersonTotal(from.getId(), into.getId()));
        periods.flush();

        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
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
}
