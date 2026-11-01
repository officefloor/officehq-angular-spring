package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.patron.PatronController.PatronView;
import net.officefloor.hq.app.patron.PatronRepository;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateController.RateView;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.holiday.HolidayController.HolidayView;
import net.officefloor.hq.app.holiday.HolidayRepository;
import net.officefloor.hq.app.role.DelegationController.DelegationView;
import net.officefloor.hq.app.role.DelegationRepository;
import net.officefloor.hq.app.role.RoleController.RoleView;
import net.officefloor.hq.app.role.RoleRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.statement.StatementController.SummaryView;
import net.officefloor.hq.app.statement.StatementRepository;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.watch.WatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A sheet's history: after each change, what the sheet holds is kept under the sheet's today, replacing what was kept
 * under that day before. The sheet as it stood at the end of a date is then the last kept under a day on or before
 * it: every change made while the sheet's today was on or before that date, and none made later.
 */
@Service
public class SheetHistory {

    /** The request header naming the date a user's screen shows the sheet as of (an ISO date): while it is sent, every
     *  read of the sheet answers as the sheet stood at the end of that date, that date standing in for its today, and
     *  every change is refused (see {@link AsOfGuard}). */
    public static final String AS_OF_HEADER = "X-As-Of";

    private final SheetSnapshotRepository snapshots;
    private final SheetRepository sheets;
    private final RateChains rates;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PeriodRepository periods;
    private final StatementRepository statements;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final RoleRepository roles;
    private final DelegationRepository delegations;
    private final HolidayRepository holidays;
    private final JsonMapper json;
    private final PoolRepository pools;
    private final PoolMemberRepository poolMembers;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;

    public SheetHistory(SheetSnapshotRepository snapshots, SheetRepository sheets, RateChains rates,
            PersonRepository people, ClaimRepository claims, PeriodRepository periods, StatementRepository statements,
            PatronRepository patrons, WatchRepository watches, RoleRepository roles, DelegationRepository delegations,
            HolidayRepository holidays, JsonMapper json, PoolRepository pools, PoolMemberRepository poolMembers,
            CrewRepository crews, CrewMemberRepository crewMembers) {
        this.pools = pools;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.crewMembers = crewMembers;
        this.snapshots = snapshots;
        this.sheets = sheets;
        this.rates = rates;
        this.people = people;
        this.claims = claims;
        this.periods = periods;
        this.statements = statements;
        this.patrons = patrons;
        this.watches = watches;
        this.roles = roles;
        this.delegations = delegations;
        this.holidays = holidays;
        this.json = json;
    }

    /** Keep what the sheet holds once the change being made is done: just before the current transaction commits,
     *  once however many changes it records; outside a transaction, at once. */
    public void keepOnCommit(String sheetId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            keep(sheetId);
            return;
        }
        String key = SheetHistory.class.getName() + ":" + sheetId;
        if (TransactionSynchronizationManager.hasResource(key)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!readOnly) {
                    keep(sheetId);
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(key);
            }
        });
    }

    /** Keep what the sheet holds now under its today. */
    @Transactional
    public void keep(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        if (sheet == null) {
            return;
        }
        List<Rate> sheetRates = rates.ratesOf(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<String, List<Long>> watched = new TreeMap<>();
        watches.findBySheetIdOrderByIdAsc(sheetId).forEach(w ->
                watched.computeIfAbsent(w.getUserName(), u -> new ArrayList<>()).add(w.getPersonId()));
        SheetState.Views views = new SheetState.Views(
                sheetPeople.stream().map(p -> PersonView.of(p, sheet.getToday())).toList(),
                sheetClaims.stream().map(ClaimView::of).map(SheetHistory::unlocked).toList(),
                viewsOf(sheetRates),
                periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().map(PeriodView::of).toList(),
                StatementService.summarize(statements.findBySheetIdOrderByIdAsc(sheetId)).stream()
                        .map(SummaryView::of).toList(),
                patrons.findBySheetIdOrderByIdAsc(sheetId).stream().map(PatronView::of).toList(),
                watched,
                roles.findBySheetIdOrderByUserName(sheetId).stream().map(RoleView::of).toList(),
                delegations.findBySheetIdOrderByIdAsc(sheetId).stream().map(DelegationView::of).toList(),
                holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(HolidayView::of).toList());
        List<SheetState.AncestorState> ancestors = rates.ancestorsOf(sheetId).stream().map(ancestor -> {
            List<Rate> ancestorRates = rates.ratesOf(ancestor);
            return new SheetState.AncestorState(ancestor, SheetState.statesOf(ancestorRates), viewsOf(ancestorRates));
        }).toList();
        SheetState state = SheetState.of(sheet, sheetRates, sheetPeople, sheetClaims, views, ancestors,
                poolStates(sheetId), crewStates(sheetId));
        snapshots.deleteBySheetIdAndAsOf(sheetId, sheet.getToday());
        snapshots.saveAndFlush(new SheetSnapshot(sheetId, sheet.getToday(), json.writeValueAsString(state)));
    }

    /** The sheet's pools as they stand now, each with its memberships in the order they were added. */
    private List<SheetState.PoolState> poolStates(String sheetId) {
        List<Pool> sheetPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<PoolMember>> byPool = poolMembers.findByPoolIdInOrderByIdAsc(
                sheetPools.stream().map(Pool::getId).toList()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        return sheetPools.stream().map(p -> new SheetState.PoolState(p.getId(), p.getName(),
                byPool.getOrDefault(p.getId(), List.of()).stream().map(m -> new SheetState.PoolMemberState(
                        m.getPersonId(), m.getMemberPoolId(), m.getShare(), m.getFrom(), m.getTo())).toList()))
                .toList();
    }

    /** The sheet's crews as they stand now, each with its members in the order they were added. */
    private List<SheetState.CrewState> crewStates(String sheetId) {
        List<Crew> sheetCrews = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<CrewMember>> byCrew = crewMembers.findByCrewIdInOrderByIdAsc(
                sheetCrews.stream().map(Crew::getId).toList()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        return sheetCrews.stream().map(c -> new SheetState.CrewState(c.getId(), c.getName(), c.getLeadId(),
                byCrew.getOrDefault(c.getId(), List.of()).stream().map(CrewMember::getPersonId).toList())).toList();
    }

    /** The sheet as it stood at the end of a date; empty when no change was made on it by then. */
    @Transactional(readOnly = true)
    public Optional<SheetState> asOf(String sheetId, LocalDate date) {
        return snapshots.findFirstBySheetIdAndAsOfLessThanEqualOrderByAsOfDescIdDesc(sheetId, date)
                .map(s -> json.readValue(s.getState(), SheetState.class));
    }

    /** The line of rates as it stood at the end of a date: the sheet's own then, then each of its ancestors' as
     *  they stood then (none for one with no change made on it by then). */
    @Transactional(readOnly = true)
    public RateChain ratesAsOf(String sheetId, LocalDate date) {
        Optional<SheetState> then = asOf(sheetId, date);
        return ratesOf(sheetId, then.orElse(null), date);
    }

    /** The line of rates of the sheet as it stood then (none: no change made on it by then): its own, then each of
     *  its ancestors' as they stood when that state was kept; for a state kept before ancestors were recorded, or
     *  none, each ancestor's as it stood at the end of the date. */
    @Transactional(readOnly = true)
    public RateChain ratesOf(String sheetId, SheetState then, LocalDate date) {
        List<Rate> own = then == null ? List.of() : then.rebuildRates(sheetId);
        if (then != null && then.ancestors() != null) {
            return new RateChain(Stream.concat(Stream.of(own),
                    then.ancestors().stream().map(SheetState.AncestorState::rebuildRates)).toList());
        }
        return new RateChain(Stream.concat(Stream.of(own), rates.ancestorsOf(sheetId).stream()
                .map(id -> asOf(id, date).map(state -> state.rebuildRates(id)).orElse(List.<Rate>of()))).toList());
    }

    /** The rates the sheet inherited at the end of a date, nearest ancestor first, as the screen reads them: each
     *  ancestor's as it stood when the sheet's state for that date was kept (see {@link #ratesOf}). */
    @Transactional(readOnly = true)
    public List<List<RateView>> inheritedAsOf(String sheetId, LocalDate date) {
        Optional<SheetState> then = asOf(sheetId, date);
        if (then.isPresent() && then.get().ancestors() != null) {
            return then.get().ancestors().stream().map(SheetState.AncestorState::views).toList();
        }
        return rates.ancestorsOf(sheetId).stream().map(ancestor -> viewsAsOf(ancestor, date).rates()).toList();
    }

    /** The rates as the screen reads them: by grade, in the order they take effect. */
    private static List<RateView> viewsOf(List<Rate> sheetRates) {
        return sheetRates.stream().sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate.BY_FROM))
                .map(RateView::of).toList();
    }

    /** The claim as kept: no one's editor lock is part of what the sheet held. */
    private static ClaimView unlocked(ClaimView c) {
        return new ClaimView(c.id(), c.personId(), c.amount(), c.date(), c.note(), c.status(), c.number(),
                c.settledDue(), c.openedBy(), c.stated(), c.statedDue(), c.corrects(), c.dueBy(), c.deferredUntil(),
                c.log(), c.proofs(), c.splitFrom(), null, c.lateEntry(), c.remindedOn(), c.ref(), c.tags(),
                c.voidReason(), c.comments(), c.editedBy(), c.batch(), c.lines(), c.waived(), c.statedLate(),
                c.proofReceipts(), c.proofPhotos(), c.held(), c.shares() == null ? List.of() : c.shares(), c.reviewAssigned());
    }

    /** The lists the screen reads of the sheet as it stood at the end of a date; none when no change was made on it
     *  by then. */
    @Transactional(readOnly = true)
    public SheetState.Views viewsAsOf(String sheetId, LocalDate date) {
        return asOf(sheetId, date).map(SheetState::views).orElse(SheetState.Views.NONE);
    }

    /** The people as the screen read them at the end of a date, each with the grade in force on it. */
    @Transactional(readOnly = true)
    public List<PersonView> peopleAsOf(String sheetId, LocalDate date) {
        return asOf(sheetId, date).map(s -> s.people(sheetId, date)).orElse(List.of());
    }

    /** The sheet as it stood at the end of a date, unsaved, with that date standing in for its today: as it was
     *  started when no change was made on it by then. Its parent, not dated, is the one it names now. */
    @Transactional(readOnly = true)
    public Sheet sheetAsOf(Sheet live, LocalDate date) {
        return asOf(live.getId(), date).map(s -> s.rebuildSheet(live, date))
                .orElseGet(() -> {
                    Sheet started = new Sheet(live.getId(), live.getCreatedBy(), live.getCreatedAt(), date);
                    started.setParentId(live.getParentId());
                    return started;
                });
    }
}
