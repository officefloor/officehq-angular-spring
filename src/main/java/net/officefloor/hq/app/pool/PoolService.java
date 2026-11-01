package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pools on a sheet and their members. */
@Service
public class PoolService {

    /** The most a member's share can be, and what a balanced pool's shares total. */
    public static final BigDecimal FULL = new BigDecimal("100.00");

    /** The most pools a chain of pools, each a member of the next, can hold. */
    public static final int MAX_DEPTH = 3;

    /** A member of a pool as it stands: a person or another pool (exactly one is set), its share, whether the
     *  person has a hold in force on the sheet's today, the membership's from-date and to-date (null: none), and
     *  whether those dates include that today. */
    public record Member(Person person, Pool pool, BigDecimal share, boolean held, LocalDate from, LocalDate to,
            boolean inForce) {

        /** What the share counts toward the pool's total: the share as given, retired or not, or nothing while the
         *  membership's dates do not include the sheet's today. */
        public BigDecimal counted() {
            return !inForce ? BigDecimal.ZERO.setScale(2) : share;
        }

        /** Whether the member is a person that is retired, whatever the date. */
        public boolean retired() {
            return person != null && person.isRetired();
        }

        /** What the share counts toward the cuts: nothing once retired or while held on the sheet's today, else what
         *  it counts; the others' cuts take up what it leaves. */
        public BigDecimal cutShare() {
            return held || retired() ? BigDecimal.ZERO.setScale(2) : counted();
        }
    }

    /** A pool with its members, in the order they were added, and its pot (null: none): the sheet's pot for a pool
     *  that is a member of none, else its cut in the pool above. */
    public record PoolWithMembers(Pool pool, List<Member> members, BigDecimal pot) {

        /** The members' shares added up, a retired member's as given, one whose dates do not include the sheet's
         *  today counting as 0.00. */
        public BigDecimal totalShare() {
            return members.stream().map(Member::counted).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        /** Balanced when the shares total exactly 100.00. */
        public boolean balanced() {
            return totalShare().compareTo(FULL) == 0;
        }

        /** Each member's cut of the pot, in the order of the members; none (null) when there is no pot or the pool
         *  is not balanced. A cut is the pot times the share over the shares of the members with a cut added up,
         *  rounded half to even to two decimals, 0.00 for a retired member, one held on the sheet's today or one whose
         *  dates do not include it (shown as no cut); what the
         *  cuts fall short of (or over) the pot goes to the member with the smallest non-zero cut, the latest
         *  added on a tie (the last member with a cut when no cut is non-zero), so the cuts total exactly the pot. When every member is retired there are none; when no
         *  member otherwise has a cut, each is 0.00. */
        public List<BigDecimal> cuts() {
            if (pot == null || !balanced() || (!members.isEmpty() && members.stream().allMatch(Member::retired))) {
                return null;
            }
            BigDecimal withCut = members.stream().map(Member::cutShare)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            if (withCut.signum() == 0) {
                return new ArrayList<>(members.stream().map(m -> BigDecimal.ZERO.setScale(2)).toList());
            }
            List<BigDecimal> cuts = new ArrayList<>(members.stream()
                    .map(m -> pot.multiply(m.cutShare()).divide(withCut, 2, RoundingMode.HALF_EVEN)).toList());
            BigDecimal remainder = pot.subtract(cuts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
            int smallest = -1;
            int lastWithShare = -1;
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).cutShare().signum() == 0) {
                    continue;
                }
                lastWithShare = i;
                if (cuts.get(i).signum() != 0
                        && (smallest < 0 || cuts.get(i).compareTo(cuts.get(smallest)) <= 0)) {
                    smallest = i;
                }
            }
            int to = smallest >= 0 ? smallest : lastWithShare;
            cuts.set(to, cuts.get(to).add(remainder));
            return cuts;
        }
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;
    private final SheetHistory history;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;
    private final PeriodRepository periods;
    private final Rollup rollup;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetStore, Audit audit, SheetHistory history, CrewRepository crews,
            CrewMemberRepository crewMembers, PeriodRepository periods, Rollup rollup) {
        this.rollup = rollup;
        this.periods = periods;
        this.history = history;
        this.crews = crews;
        this.crewMembers = crewMembers;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added, each with its members and its pot; then each crew, in the
     *  order they were added, as a pool under its name (see {@link #crewMembers}). */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId) {
        return list(sheetId, null);
    }

    /** The sheet's pools as {@link #list(String)}, with {@code on} (null: the sheet's today) standing in for the
     *  sheet's today, as a sheet shown as of a date reads them: then the pot and the people (retired or held) are as
     *  they stood at the end of that date (see {@link SheetHistory}), a person not on the sheet by then is no member,
     *  and the pools are as they are now, each with its memberships as they stood then when the state kept them (so a
     *  membership since moved by a merge counts for the person who held it then), else as they are now. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId, LocalDate on) {
        return list(sheetId, on, null);
    }

    /** The sheet's pools as {@link #list(String, LocalDate)}; read live with a {@code preview} date, that date stands
     *  in for the sheet's today over its current records. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> list(String sheetId, LocalDate on, LocalDate preview) {
        Optional<SheetState> then = on == null ? Optional.empty() : history.asOf(sheetId, on);
        BigDecimal sheetPot = on != null ? then.map(SheetState::pot).orElse(null)
                : sheetStore.findById(sheetId).map(Sheet::getPot).orElse(null);
        LocalDate today = on != null ? on : preview != null ? preview
                : sheetStore.findById(sheetId).map(Sheet::getToday).orElse(null);
        List<Person> sheetPeople = on != null ? then.map(s -> s.rebuildPeople(sheetId)).orElse(List.of())
                : people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        List<Pool> list = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(list.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        if (then.isPresent() && then.get().pools() != null) {
            byPool = new HashMap<>(byPool);
            for (SheetState.PoolState p : then.get().pools()) {
                byPool.put(p.id(), membersOf(p));
            }
        }
        List<Crew> sheetCrews = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<CrewMember>> byCrew = crewMembers.findByCrewIdInOrderByIdAsc(
                sheetCrews.stream().map(Crew::getId).toList()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        return build(sheetId, list, byPool, sheetCrews, byCrew, sheetPeople, sheetPot, today);
    }

    /** The sheet's pools as they stood at the end of a date, that date standing in for the sheet's today: the pools,
     *  their memberships, the crews, the pot and the people all as they were then (see {@link SheetHistory}); none
     *  when no change was made on the sheet by then. A state kept before pools were recorded has them as they are
     *  now. */
    @Transactional(readOnly = true)
    public List<PoolWithMembers> stoodOn(String sheetId, LocalDate date) {
        Optional<SheetState> then = history.asOf(sheetId, date);
        if (then.isEmpty()) {
            return List.of();
        }
        SheetState state = then.get();
        if (state.pools() == null || state.crews() == null) {
            return list(sheetId, date);
        }
        List<Pool> list = new ArrayList<>();
        Map<Long, List<PoolMember>> byPool = new HashMap<>();
        for (SheetState.PoolState p : state.pools()) {
            list.add(Pool.rebuilt(sheetId, p.id(), p.name()));
            byPool.put(p.id(), membersOf(p));
        }
        List<Crew> sheetCrews = new ArrayList<>();
        Map<Long, List<CrewMember>> byCrew = new HashMap<>();
        for (SheetState.CrewState c : state.crews()) {
            sheetCrews.add(Crew.rebuilt(sheetId, c.id(), c.name(), c.leadId()));
            byCrew.put(c.id(), c.members().stream().map(id -> new CrewMember(c.id(), id)).toList());
        }
        return build(sheetId, list, byPool, sheetCrews, byCrew, state.rebuildPeople(sheetId), state.pot(), date);
    }

    /** A kept pool's memberships, rebuilt unsaved in the order they were added. */
    private static List<PoolMember> membersOf(SheetState.PoolState p) {
        return p.members().stream().map(m -> {
            PoolMember member = m.memberPoolId() != null ? PoolMember.ofPool(p.id(), m.memberPoolId(), m.share())
                    : new PoolMember(p.id(), m.personId(), m.share());
            member.setDates(m.from(), m.to());
            return member;
        }).toList();
    }

    /** A member's cut at the end of a period: the period's name and the cut (null: none). */
    public record PeriodCut(String period, BigDecimal cut) {
    }

    /** A pool with, for each of its members in their order, the member's cut for each period whose to-date is on or
     *  before the sheet's today or that is closed or soft-closed, in the order of the periods' from-dates. */
    public record PoolWithHistory(PoolWithMembers pool, List<List<PeriodCut>> periodCuts) {
    }

    /** A period whose cuts are shown: its name, its to-date and, when closed or soft-closed, the cuts fixed then (by
     *  {@code <pool id>/<member key>}; null while live). */
    private record ShownPeriod(String name, LocalDate to, Map<String, BigDecimal> fixed) {
    }

    /** The sheet's pools as {@link #list(String, LocalDate)}, each member with its cut for each period whose to-date
     *  is on or before the sheet's today (or {@code on}) or that is closed or soft-closed. A closed or soft-closed
     *  period shows the cut fixed at the moment it was closed (none when the member had no cut shown then); any
     *  other shows the cut as it stood at the end of the period: the pool, its members, the pot and the holds as they
     *  were then, with that day as the sheet's today (see {@link #stoodOn}); none when the member was not in the pool
     *  then, the pool showed no cuts or the membership was not in force on that day. */
    @Transactional(readOnly = true)
    public List<PoolWithHistory> listWithHistory(String sheetId, LocalDate on) {
        return listWithHistory(sheetId, on, null);
    }

    /** As {@link #listWithHistory(String, LocalDate)}; read live with a {@code preview} date, that date standing in for
     *  the sheet's today over its current records. */
    @Transactional(readOnly = true)
    public List<PoolWithHistory> listWithHistory(String sheetId, LocalDate on, LocalDate preview) {
        List<PoolWithMembers> now = list(sheetId, on, preview);
        LocalDate today = on != null ? on : preview != null ? preview
                : sheetStore.findById(sheetId).map(Sheet::getToday).orElse(null);
        Map<String, Period> stored = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .collect(Collectors.toMap(Period::getName, Function.identity()));
        List<ShownPeriod> shown = new ArrayList<>();
        if (today != null) {
            if (on != null) {
                history.viewsAsOf(sheetId, on).periods().stream()
                        .filter(p -> p.closed() || p.softClosed() || !p.to().isAfter(today))
                        .sorted(Comparator.comparing(PeriodView::from))
                        .forEach(p -> {
                            Period current = stored.get(p.name());
                            boolean fixed = (p.closed() || p.softClosed()) && current != null && current.isFixed();
                            if (fixed || !p.to().isAfter(today)) {
                                shown.add(new ShownPeriod(p.name(), p.to(), fixed ? current.getClosedPoolCuts() : null));
                            }
                        });
            } else {
                stored.values().stream().sorted(Comparator.comparing(Period::getFromDate))
                        .filter(p -> p.isFixed() || !p.getToDate().isAfter(today))
                        .forEach(p -> shown.add(new ShownPeriod(p.getName(), p.getToDate(),
                                p.isFixed() ? p.getClosedPoolCuts() : null)));
            }
        }
        // Each live period's cuts, by pool id then by member key.
        Map<LocalDate, Map<Long, Map<String, BigDecimal>>> byDay = new HashMap<>();
        for (ShownPeriod period : shown) {
            if (period.fixed() == null) {
                byDay.computeIfAbsent(period.to(), day -> cutsOf(stoodOn(sheetId, day)));
            }
        }
        return now.stream().map(p -> new PoolWithHistory(p, p.members().stream().map(m -> {
            List<PeriodCut> cuts = new ArrayList<>();
            for (ShownPeriod period : shown) {
                cuts.add(new PeriodCut(period.name(), period.fixed() != null
                        ? period.fixed().get(fixedKey(p.pool().getId(), m))
                        : byDay.get(period.to()).getOrDefault(p.pool().getId(), Map.of()).get(keyOf(m))));
            }
            return cuts;
        }).toList())).toList();
    }

    /** Each pool member's shown cut as it stands now, by {@code <pool id>/<member key>}: what a period closed or
     *  soft-closed now fixes. */
    public Map<String, BigDecimal> cutsNow(String sheetId) {
        Map<String, BigDecimal> made = new HashMap<>();
        cutsOf(list(sheetId, null)).forEach((poolId, of) ->
                of.forEach((key, cut) -> made.put(poolId + "/" + key, cut)));
        return made;
    }

    /** Each person's cut total, by person id, with {@code on} (null: the sheet's today) standing in for the sheet's
     *  today as for {@link #list(String, LocalDate)}: their shown cuts across every pool they are a member of, added
     *  up; a person with no cut shown is left out. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> cutTotals(String sheetId, LocalDate on) {
        return cutTotals(sheetId, on, null);
    }

    /** As {@link #cutTotals(String, LocalDate)}; read live with a {@code preview} date, that date standing in for the
     *  sheet's today. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> cutTotals(String sheetId, LocalDate on, LocalDate preview) {
        Map<Long, BigDecimal> byPerson = new HashMap<>();
        for (PoolWithMembers p : list(sheetId, on, preview)) {
            List<BigDecimal> cuts = p.cuts();
            for (int i = 0; cuts != null && i < p.members().size(); i++) {
                Member m = p.members().get(i);
                if (m.person() != null && cuts.get(i) != null && m.inForce()) {
                    byPerson.merge(m.person().getId(), cuts.get(i), BigDecimal::add);
                }
            }
        }
        return byPerson;
    }

    /** A member's key among the cuts a closed period fixed. */
    private static String fixedKey(Long poolId, Member m) {
        return poolId + "/" + keyOf(m);
    }

    /** Each pool's shown cuts, by pool id then by member key; a member with no cut shown is left out. */
    private static Map<Long, Map<String, BigDecimal>> cutsOf(List<PoolWithMembers> list) {
        Map<Long, Map<String, BigDecimal>> made = new HashMap<>();
        for (PoolWithMembers p : list) {
            List<BigDecimal> cuts = p.cuts();
            Map<String, BigDecimal> of = new HashMap<>();
            for (int i = 0; cuts != null && i < p.members().size(); i++) {
                Member m = p.members().get(i);
                if (cuts.get(i) != null && m.inForce()) {
                    of.put(keyOf(m), cuts.get(i));
                }
            }
            made.put(p.pool().getId(), of);
        }
        return made;
    }

    /** A member's key within its pool: the person's id, or pool-id for a pool. */
    private static String keyOf(Member m) {
        return m.pool() != null ? "pool-" + m.pool().getId() : String.valueOf(m.person().getId());
    }

    /** The pools with their members and pots, from the stored pools and memberships, the crews, the people (one not
     *  among them is no member), the sheet's pot and the day standing in for the sheet's today. */
    private static List<PoolWithMembers> build(String sheetId, List<Pool> list, Map<Long, List<PoolMember>> byPool,
            List<Crew> sheetCrews, Map<Long, List<CrewMember>> byCrew, List<Person> sheetPeople,
            BigDecimal sheetPot, LocalDate today) {
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, Pool> poolsById = new HashMap<>(list.stream().collect(Collectors.toMap(Pool::getId, Function.identity())));
        Map<Long, List<Member>> membersOf = new HashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Pool p : list) {
            List<Member> of = new ArrayList<>();
            for (PoolMember m : byPool.getOrDefault(p.getId(), List.of())) {
                if (m.getMemberPoolId() != null) {
                    Pool child = poolsById.get(m.getMemberPoolId());
                    if (child != null) {
                        of.add(new Member(null, child, m.getShare(), false, m.getFrom(), m.getTo(),
                                m.inForceOn(today)));
                        parentOf.put(child.getId(), p.getId());
                    }
                } else if (byId.containsKey(m.getPersonId())) {
                    Person person = byId.get(m.getPersonId());
                    of.add(new Member(person, null, m.getShare(), person.heldOn(today), m.getFrom(),
                            m.getTo(), m.inForceOn(today)));
                }
            }
            membersOf.put(p.getId(), of);
        }
        list = new ArrayList<>(list);
        for (Crew c : sheetCrews) {
            Pool crewPool = Pool.ofCrew(sheetId, c.getId(), c.getName());
            List<Person> crewPeople = new ArrayList<>();
            if (c.getLeadId() != null && byId.containsKey(c.getLeadId())) {
                crewPeople.add(byId.get(c.getLeadId()));
            }
            for (CrewMember m : byCrew.getOrDefault(c.getId(), List.of())) {
                if (byId.containsKey(m.getPersonId())) {
                    crewPeople.add(byId.get(m.getPersonId()));
                }
            }
            list.add(crewPool);
            poolsById.put(crewPool.getId(), crewPool);
            membersOf.put(crewPool.getId(), crewMembers(crewPeople, today));
        }
        Map<Long, PoolWithMembers> done = new HashMap<>();
        return list.stream().map(p -> withPot(p.getId(), poolsById, membersOf, parentOf, sheetPot, done, 0)).toList();
    }

    /** A crew's people, its lead first (when still on the sheet) then its members in the order added, as the members
     *  of its pool: 100.00 split equally, each share rounded half to even, with the remainder given to the lead (the
     *  first), each membership with no from-date or to-date. */
    private static List<Member> crewMembers(List<Person> crewPeople, LocalDate today) {
        if (crewPeople.isEmpty()) {
            return List.of();
        }
        BigDecimal each = FULL.divide(BigDecimal.valueOf(crewPeople.size()), 2, RoundingMode.HALF_EVEN);
        BigDecimal first = FULL.subtract(each.multiply(BigDecimal.valueOf(crewPeople.size() - 1)));
        List<Member> of = new ArrayList<>();
        for (int i = 0; i < crewPeople.size(); i++) {
            Person person = crewPeople.get(i);
            of.add(new Member(person, null, i == 0 ? first : each, person.heldOn(today), null, null, true));
        }
        return of;
    }

    /** The pool with its members and its pot: the sheet's pot when it is a member of none, else its cut in the pool
     *  above (none when that pool shows no cuts or its membership there is not in force on the sheet's today). */
    private static PoolWithMembers withPot(Long poolId, Map<Long, Pool> poolsById, Map<Long, List<Member>> membersOf,
            Map<Long, Long> parentOf, BigDecimal sheetPot, Map<Long, PoolWithMembers> done, int depth) {
        PoolWithMembers known = done.get(poolId);
        if (known != null) {
            return known;
        }
        Long parentId = parentOf.get(poolId);
        BigDecimal pot = sheetPot;
        if (parentId != null) {
            pot = null;
            if (depth <= poolsById.size()) {
                PoolWithMembers parent = withPot(parentId, poolsById, membersOf, parentOf, sheetPot, done, depth + 1);
                List<BigDecimal> cuts = parent.cuts();
                if (cuts != null) {
                    for (int i = 0; i < parent.members().size(); i++) {
                        Member member = parent.members().get(i);
                        if (member.pool() != null && member.pool().getId().equals(poolId) && member.inForce()) {
                            pot = cuts.get(i);
                        }
                    }
                }
            }
        }
        PoolWithMembers made = new PoolWithMembers(poolsById.get(poolId), membersOf.get(poolId), pot);
        done.put(poolId, made);
        return made;
    }

    /** Add a pool by its name, trimmed; refused when blank or already a pool or a crew on the sheet. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A pool has a name");
        }
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet");
        }
        if (crews.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That is a crew on the sheet");
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, key));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet");
        }
        audit.recordChangeOnCommit(sheetId, user, "POOL_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName());
        return saved;
    }

    /** Add a member to the pool with a share percent: a person of the sheet (personId) or another pool of it
     *  (memberPoolId), exactly one given. Refused when the share is not more than 0.00 and at most 100.00 with up to
     *  two decimals, when the person is already in it, or when the pool member is already a member of a pool, would
     *  contain itself through any chain, or would make a chain of pools more than three deep. The membership may
     *  carry a from-date and a to-date, each optional; refused when the to-date is before the from-date. */
    @Transactional
    public PoolWithMembers addMember(String sheetId, String user, long poolId, Long personId, Long memberPoolId,
            BigDecimal share, LocalDate from, LocalDate to) {
        if ((personId == null) == (memberPoolId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A member is a person or a pool");
        }
        if (share == null || share.signum() <= 0 || share.compareTo(FULL) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A share is more than 0.00 and at most 100.00, with up to two decimals");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A to-date is not before the from-date");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Pool pool = pools.findById(poolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool on this sheet"));
        // A member moves the cuts, which the cap counts against: record how the figures moved.
        Rollup.Figures before = rollup.of(sheetId);
        String name;
        PoolMember member;
        if (personId != null) {
            Person person = people.findById(personId).filter(p -> p.getSheetId().equals(sheetId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
            if (members.existsByPoolIdAndPersonId(pool.getId(), personId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
            }
            name = person.getName();
            member = new PoolMember(pool.getId(), personId, scaled);
        } else {
            Pool child = pools.findById(memberPoolId).filter(p -> p.getSheetId().equals(sheetId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool on this sheet"));
            checkNesting(sheetId, pool, child);
            name = child.getName();
            member = PoolMember.ofPool(pool.getId(), child.getId(), scaled);
        }
        member.setDates(from, to);
        try {
            members.saveAndFlush(member);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That member is already in a pool");
        }
        audit.recordChangeOnCommit(sheetId, user, "POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user
                + " pool=" + pool.getName() + " name=" + name + " share=" + scaled.toPlainString() + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(sheetId, user, before);
        return list(sheetId).stream().filter(p -> p.pool().getId().equals(pool.getId())).findFirst().orElseThrow();
    }

    /** Refuse making the child pool a member of the parent when it is already a member of a pool, when the parent
     *  is the child or within it, or when the chain of pools would be more than {@link #MAX_DEPTH} deep. */
    private void checkNesting(String sheetId, Pool parent, Pool child) {
        if (members.findByMemberPoolId(child.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool");
        }
        List<Long> ids = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(Pool::getId).toList();
        Map<Long, Long> parentOf = new HashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(ids)) {
            if (m.getMemberPoolId() != null) {
                parentOf.put(m.getMemberPoolId(), m.getPoolId());
                childrenOf.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
            }
        }
        // How deep the parent sits: 1 for a pool that is a member of none.
        int above = 0;
        for (Long at = parent.getId(); at != null && above <= ids.size(); at = parentOf.get(at)) {
            if (at.equals(child.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot contain itself");
            }
            above++;
        }
        if (above + height(child.getId(), childrenOf, 0, ids.size()) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools is at most " + MAX_DEPTH + " deep");
        }
    }

    /** How many pools deep the chain below a pool goes, counting the pool itself. */
    private static int height(Long poolId, Map<Long, List<Long>> childrenOf, int depth, int limit) {
        int most = 0;
        if (depth <= limit) {
            for (Long c : childrenOf.getOrDefault(poolId, List.of())) {
                most = Math.max(most, height(c, childrenOf, depth + 1, limit));
            }
        }
        return 1 + most;
    }
}
