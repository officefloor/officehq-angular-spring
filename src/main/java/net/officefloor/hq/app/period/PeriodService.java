package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Action;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods of each sheet: uniquely named, none overlapping another. A period can be closed, which
 * fixes its due-total then, and a steward (or the roles the permission matrix allows) can reopen it, which makes its due-total live again. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SettingsService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            ClaimLogRepository logs, PersonRepository people, RollupService rollup, SettingsService settings, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.logs = logs;
        this.people = people;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("to before from");
        }
        if (periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(name)).isPresent()) {
            throw new PeriodRefusedException("name taken");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("overlaps");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.change("PERIOD_ADDED", sheetId, user, "period=" + period.getName(), "from=" + period.getFrom(),
                "to=" + period.getTo());
        return period;
    }

    /** Close the period, fixing its due-total now; refused when the user's role may not close (see
     * {@link SettingsService#allowed}) or it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.CLOSE)) {
            throw new PeriodNotStewardException();
        }
        if (period.isClosed()) {
            throw new PeriodRefusedException("already closed");
        }
        period.close(personTotalsOf(period));
        periods.flush();
        audit.change("PERIOD_CLOSED", sheetId, user, "period=" + period.getName(),
                "total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Reopen the period, making its due-total live again; refused when the user's role may not
     * reopen (a steward's by default, see {@link SettingsService#allowed}) or the period is not closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.REOPEN)) {
            throw new PeriodNotStewardException();
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("not closed");
        }
        period.reopen();
        periods.flush();
        audit.change("PERIOD_REOPENED", sheetId, user, "period=" + period.getName());
        return period;
    }

    /** Each person's due-total for the period now, by person id, for everyone on the sheet: the sum of
     * what is due on their claims dated in it that counted by the status each had at the end of the
     * period's last day (see {@link #statusAt}), as each shows it now, late charges included (a
     * claim with no figure adds nothing, nor does an OPEN claim deferred on the sheet's today or an unbound OPEN claim dated within one of its person's holds, while the claim is still OPEN or PENDING now). */
    private Map<Long, BigDecimal> personTotalsOf(Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(period.getSheetId()).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Long id : byId.keySet()) totals.put(id, BigDecimal.ZERO.setScale(2));
        LocalDate today = sheets.findById(period.getSheetId()).map(Sheet::getToday).orElse(null);
        List<Claim> dated = claims.findBySheetIdOrderByListedDescIdDesc(period.getSheetId()).stream()
                .filter(claim -> period.overlaps(claim.getDate(), claim.getDate())).toList();
        Map<Long, List<ClaimLog>> logsOf = dated.isEmpty() ? Map.of()
                : logs.findByClaimIdInOrderByIdAsc(dated.stream().map(Claim::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ClaimLog::getClaimId));
        for (Claim claim : dated) {
            Person person = byId.get(claim.getPersonId());
            ClaimStatus status = statusAt(logsOf.getOrDefault(claim.getId(), List.of()), period.getTo());
            if (person == null || status == null) continue;
            boolean counts = claim.getStatus().isOpen() ? RollupService.countsAs(claim, status, person, today) : status.counts();
            if (!counts) continue;
            BigDecimal figure = rollup.shownOf(claim, person);
            if (figure != null) totals.merge(person.getId(), figure, BigDecimal::add);
        }
        return totals;
    }

    /** The status a claim had at the end of {@code day}, by its status log, oldest first, whose lines
     * are dated with the sheet's today when each change was made; null when it was added after. */
    private static ClaimStatus statusAt(List<ClaimLog> log, LocalDate day) {
        ClaimStatus status = null;
        for (ClaimLog line : log) {
            if (line.getDate().isAfter(day)) break;
            status = line.getStatus();
        }
        return status;
    }
}
