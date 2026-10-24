package net.officefloor.hq.app.rollup;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.SheetRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A steward's dashboard over a sheet: how many of its claims are PENDING, how many are in REVIEW, and
 * how many are overdue among those that count in the live totals (see {@link RollupService#countsLive}
 * and {@link RollupService#isOverdue}). Only a steward sees it; anyone else sees no counts. */
@Service
public class DashboardService {

    /** The counts; each null when the user is not a steward. */
    public record Counts(Integer pending, Integer review, Integer overdue) {
        static final Counts NONE = new Counts(null, null, null);
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final SettingsService settings;
    private final SheetHistory history;

    public DashboardService(PersonRepository people, ClaimRepository claims, RollupService rollup,
            SettingsService settings, SheetHistory history) {
        this.people = people;
        this.claims = claims;
        this.rollup = rollup;
        this.settings = settings;
        this.history = history;
    }

    /** The user's dashboard over the sheet as it stands. */
    @Transactional(readOnly = true)
    public Counts live(String sheetId, String user) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) return Counts.NONE;
        return countsOf(people.findBySheetIdOrderByPositionAscIdAsc(sheetId),
                claims.findBySheetIdOrderByListedDescIdDesc(sheetId), rollup.live(sheetId));
    }

    /** The user's dashboard over the sheet as it stood at the end of {@code date}, for the role they had
     * then: the role given by then, or a steward by a delegation made by then whose range covers the date. */
    @Transactional(readOnly = true)
    public Counts asOf(String sheetId, String user, LocalDate date) {
        SheetAsOf then = history.asOf(sheetId, date);
        if (roleThen(then, user, date) != Role.STEWARD) return Counts.NONE;
        return countsOf(then.people(), then.claims(), then);
    }

    private static Role roleThen(SheetAsOf then, String user, LocalDate date) {
        Role given = then.roles().stream().filter(r -> r.getUser().equals(user)).map(SheetRole::getRole)
                .findFirst().orElse(Role.CLERK);
        if (given == Role.STEWARD) return given;
        boolean delegated = then.delegations().stream().anyMatch(d -> d.getUser().equals(user) && d.covers(date));
        return delegated ? Role.STEWARD : given;
    }

    private static Counts countsOf(List<Person> everyone, List<Claim> all, RollupService.Basis basis) {
        Map<Long, Person> byId = everyone.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        int pending = 0;
        int review = 0;
        int overdue = 0;
        for (Claim claim : all) {
            if (claim.getStatus() == ClaimStatus.PENDING) pending++;
            if (claim.getStatus() == ClaimStatus.REVIEW) review++;
            Person person = byId.get(claim.getPersonId());
            if (person != null && RollupService.countsLive(claim, person, basis.today())
                    && RollupService.isOverdue(claim, basis)) overdue++;
        }
        return new Counts(pending, review, overdue);
    }
}
