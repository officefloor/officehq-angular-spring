package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetHistory;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's pools: each a name, at most once on the sheet, and its members, each a person on the
 * sheet at most once per pool with a share percent (two decimals, above 0.00 and at most 100.00).
 * A pool's total adds up its members' shares, a retired member's (one with a retirement date set,
 * whatever the date) included as given; the pool is balanced when the total is exactly 100.00.
 * <p>
 * With a pot set on the sheet, each member of a balanced pool has a cut: the pot times their share
 * divided by 100, rounded half to even to two decimals. The remainder, the
 * pot less the cuts added up, goes to the member with the smallest non-zero cut (the latest added
 * on a tie; a member with no cut gets none, and a pool with no non-zero cut gives it to the last
 * member with a share), so the cuts total exactly the pot. An unbalanced pool, or no pot, has no cuts.
 * <p>
 * A retired person, and a person with a hold in force on the sheet's today (one whose dates include
 * today), has a cut of 0.00 in every pool, and their share is spread over the members left with a
 * cut: each of those cuts the pot times their share divided by the shares of the members with a cut
 * added up, half to even, the remainder as above (all 0.00 when no member is left with a cut). A
 * pool whose every member within their dates is retired shows no cuts. The pool's total share and
 * balance are unchanged by a hold or a retirement.
 * <p>
 * A member may instead be another pool, with a share like a person's. A pool is a member of at most
 * one pool, never contains itself through any chain, and a chain of pools is at most three deep. A
 * pool that is a member of another has no pot of its own: its pot is its cut in the pool above (so
 * an unbalanced pool above, or none, leaves it with no cuts). The sheet's pot is the pot of a pool
 * that is a member of none.
 * <p>
 * A membership may carry a from-date and a to-date (both inclusive, either optional; a to-date
 * before the from-date is refused). A member whose dates do not include the sheet's today is left
 * out entirely: not in the total share, not in the balance, and with no cut (blank).
 * <p>
 * Each crew is also a pool, listed after the pools under the crew's name: its lead and then its
 * members in the order added, at equal shares of 100.00 (each rounded half to even, the remainder on
 * the lead). It cuts the sheet's pot like any pool that is a member of none, but it is not stored as
 * a pool: it takes no members added to it and is never a member of a pool. A pool may not take a
 * crew's name.
 * <p>
 * For every period whose to-date is on or before the sheet's today, each member's line also shows
 * their cut as it stood at the end of that period: the sheet as {@link SheetHistory} kept it then
 * (the pools, their members, the pot and the holds), with that day as its today; blank when the
 * member, or a cut, was not there then.
 * <p>
 * Closing or soft-closing a period instead fixes every member's cut for it as it stood at that moment
 * (see {@link #cutsNow}): from then on the period shows those, whatever its to-date, blank for a member
 * not there then. Reopening it makes its cuts live again.
 */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** The most pools in a chain, each a member of the one before. */
    private static final int MAX_DEPTH = 3;

    /**
     * A member as listed: the person's id (0 for a pool), the member pool's name (null for a person),
     * the current name, their share, whether they are retired, and their cut of the pool's pot (two
     * decimals; blank when the pool is unbalanced or has no pot, or the member's dates leave out today).
     */
    public record MemberView(long personId, String pool, String name, String share, boolean retired, String cut,
            List<PeriodCut> cuts) {

        MemberView withCuts(List<PeriodCut> cuts) {
            return new MemberView(personId, pool, name, share, retired, cut, cuts);
        }
    }

    /** A member's cut as it stood at the end of a period (two decimals; blank for none then). */
    public record PeriodCut(String period, String cut) {
    }

    /** A pool as listed: its members in the order they were added, their total share (two decimals) and balance. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced,
            boolean crew) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;
    private final PeriodRepository periods;
    private final SheetHistory history;
    private final EntityManager entities;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate rolledBack;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetRows, Audit audit, RoleService roles, CrewRepository crews,
            CrewMemberRepository crewMembers, PeriodRepository periods, SheetHistory history,
            EntityManager entities, PlatformTransactionManager transactions) {
        this.periods = periods;
        this.history = history;
        this.entities = entities;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.rolledBack = new TransactionTemplate(transactions);
        this.crews = crews;
        this.crewMembers = crewMembers;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    /**
     * The pools as they stand now, each member with their cut for every period closed or soft-closed
     * (as fixed then) and every other period whose to-date is on or before the sheet's today (in the
     * periods' order). The latter are read from the sheet restored as it stood at the end of each such
     * day, in a transaction that is then rolled back (the request's own when it is answered as of a
     * date, see {@link net.officefloor.hq.app.sheet.AsOfFilter}).
     */
    public List<PoolView> list(String sheetId) {
        record Shown(String name, Map<String, String> fixed, LocalDate end) {
        }
        record Now(List<PoolView> views, List<Shown> shown) {
        }
        Now now = readOnly.execute(status -> {
            LocalDate today = sheetRows.findById(sheetId).map(s -> s.getToday()).orElse(null);
            List<Shown> shown = new ArrayList<>();
            for (Period p : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
                if (p.isClosed() || p.isSoftClosed()) {
                    shown.add(new Shown(p.getName(), Map.copyOf(p.getClosedCuts()), null));
                } else if (today != null && !p.getToDate().isAfter(today)) {
                    shown.add(new Shown(p.getName(), null, p.getToDate()));
                }
            }
            return new Now(current(sheetId), shown);
        });
        if (now.views().isEmpty()) {
            return now.views();
        }
        List<Map<String, String>> then = new ArrayList<>();
        for (Shown s : now.shown()) {
            then.add(s.fixed());
        }
        if (now.shown().stream().anyMatch(s -> s.fixed() == null)) {
            rolledBack.executeWithoutResult(status -> {
                status.setRollbackOnly();
                for (int i = 0; i < now.shown().size(); i++) {
                    Shown s = now.shown().get(i);
                    if (s.fixed() != null) {
                        continue;
                    }
                    entities.flush();
                    history.restore(sheetId, s.end());
                    // the rows were put back behind the persistence context's back: read them afresh
                    entities.clear();
                    then.set(i, cutsOf(current(sheetId)));
                }
                entities.clear();
            });
        }
        List<PoolView> out = new ArrayList<>();
        for (PoolView pool : now.views()) {
            List<MemberView> views = new ArrayList<>();
            for (MemberView m : pool.members()) {
                List<PeriodCut> cuts = new ArrayList<>();
                for (int i = 0; i < now.shown().size(); i++) {
                    cuts.add(new PeriodCut(now.shown().get(i).name(), then.get(i).getOrDefault(key(pool, m), "")));
                }
                views.add(m.withCuts(cuts));
            }
            out.add(new PoolView(pool.id(), pool.name(), views, pool.totalShare(), pool.balanced(), pool.crew()));
        }
        return out;
    }

    /**
     * Every member's cut as the pools stand now, by the member's place on the sheet (blank for none):
     * what a closing period fixes. Read within the caller's transaction.
     */
    public Map<String, String> cutsNow(String sheetId) {
        return cutsOf(current(sheetId));
    }

    /**
     * Each person's cut total as the pools stand now, by person id: their cuts across every pool
     * (crews' included) they are a member of; a person with no cut is left out. Read within the
     * caller's transaction.
     */
    public Map<Long, BigDecimal> cutTotalsNow(String sheetId) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (PoolView pool : current(sheetId)) {
            for (MemberView m : pool.members()) {
                if (m.pool() == null && !m.cut().isEmpty()) {
                    totals.merge(m.personId(), new BigDecimal(m.cut()), BigDecimal::add);
                }
            }
        }
        return totals;
    }

    private static Map<String, String> cutsOf(List<PoolView> pools) {
        Map<String, String> cuts = new HashMap<>();
        for (PoolView pool : pools) {
            for (MemberView m : pool.members()) {
                cuts.put(key(pool, m), m.cut());
            }
        }
        return cuts;
    }

    /** A member's place on the sheet, the same over time: the pool (a crew's by its negated id) and the person or pool. */
    private static String key(PoolView pool, MemberView m) {
        return pool.id() + "/" + (m.pool() == null ? "person:" + m.personId() : "pool:" + m.pool());
    }

    /** The pools as the store holds them, with no cuts of past periods. */
    private List<PoolView> current(String sheetId) {
        List<Pool> all = new ArrayList<>(pools.findBySheetIdOrderByIdAsc(sheetId));
        List<Crew> crewList = crews.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty() && crewList.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, Pool> poolsById = all.stream().collect(Collectors.toMap(Pool::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members
                .findByPoolIdInOrderByIdAsc(poolsById.keySet()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId, HashMap::new, Collectors.toList()));
        Set<Long> crewPools = new HashSet<>();
        if (!crewList.isEmpty()) {
            Map<Long, List<CrewMember>> byCrew = crewMembers
                    .findByCrewIdInOrderByIdAsc(crewList.stream().map(Crew::getId).toList()).stream()
                    .collect(Collectors.groupingBy(CrewMember::getCrewId));
            for (Crew crew : crewList) {
                // a crew's pool goes by the crew's id negated, so it never meets a stored pool's
                Pool pool = Pool.ofCrew(-crew.getId(), sheetId, crew.getName());
                all.add(pool);
                crewPools.add(pool.getId());
                byPool.put(pool.getId(), crewShares(crew, byCrew.getOrDefault(crew.getId(), List.of()), byId));
            }
        }
        var sheet = sheetRows.findById(sheetId);
        BigDecimal pot = sheet.map(s -> s.getPot()).orElse(null);
        LocalDate today = sheet.map(s -> s.getToday()).orElse(null);
        Map<Long, Shares> shares = new LinkedHashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Pool pool : all) {
            shares.put(pool.getId(), shares(pool, byPool.getOrDefault(pool.getId(), List.of()), byId, poolsById, today));
            for (PoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                if (m.getMemberPoolId() != null) {
                    parentOf.put(m.getMemberPoolId(), pool.getId());
                }
            }
        }
        Map<Long, List<BigDecimal>> cuts = new HashMap<>();
        List<PoolView> out = new ArrayList<>();
        for (Pool pool : all) {
            Shares sh = shares.get(pool.getId());
            List<BigDecimal> poolCuts = cutsOf(pool.getId(), shares, parentOf, pot, cuts, new HashSet<>());
            List<MemberView> views = new ArrayList<>();
            for (int i = 0; i < sh.listed().size(); i++) {
                Listed l = sh.listed().get(i);
                views.add(new MemberView(l.personId(), l.pool(), l.name(), sh.shares().get(i).toPlainString(),
                        l.retired(), poolCuts == null || poolCuts.get(i) == null ? "" : poolCuts.get(i).toPlainString(),
                        List.of()));
            }
            out.add(new PoolView(pool.getId(), pool.getName(), views, sh.total().toPlainString(), sh.balanced(),
                    crewPools.contains(pool.getId())));
        }
        return out;
    }

    /**
     * A crew's members as a pool's: the lead and then the members still on the sheet, at equal shares
     * of 100.00 each rounded half to even, the remainder on the lead.
     */
    private static List<PoolMember> crewShares(Crew crew, List<CrewMember> crewMembers, Map<Long, Person> byId) {
        List<Long> ids = new ArrayList<>();
        if (byId.containsKey(crew.getLeadId())) {
            ids.add(crew.getLeadId());
        }
        for (CrewMember m : crewMembers) {
            if (byId.containsKey(m.getPersonId()) && !ids.contains(m.getPersonId())) {
                ids.add(m.getPersonId());
            }
        }
        List<PoolMember> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        BigDecimal each = HUNDRED.divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);
        BigDecimal remainder = HUNDRED.subtract(each.multiply(BigDecimal.valueOf(ids.size())));
        for (int i = 0; i < ids.size(); i++) {
            BigDecimal share = i == 0 && byId.containsKey(crew.getLeadId()) ? each.add(remainder) : each;
            out.add(new PoolMember(crew.getSheetId(), -crew.getId(), ids.get(i), share));
        }
        return out;
    }

    /**
     * A member as worked out: a person (with their id) or a pool (its id and name; the person id 0),
     * whether a hold is in force on the sheet's today, and whether the membership's dates include it.
     */
    private record Listed(long personId, Long memberPoolId, String pool, String name, boolean retired,
            boolean held, boolean current) {
    }

    /** A pool's listed members, their shares, the total of the live ones and whether that is 100.00. */
    private record Shares(List<Listed> listed, List<BigDecimal> shares, BigDecimal total, boolean balanced) {
    }

    private static Shares shares(Pool pool, List<PoolMember> poolMembers, Map<Long, Person> byId,
            Map<Long, Pool> poolsById, LocalDate today) {
        List<Listed> listed = new ArrayList<>();
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (PoolMember m : poolMembers) {
            Listed l;
            if (m.getMemberPoolId() != null) {
                Pool member = poolsById.get(m.getMemberPoolId());
                if (member == null) {
                    continue;
                }
                l = new Listed(0, member.getId(), member.getName(), member.getName(), false, false,
                        m.covers(today));
            } else {
                Person p = byId.get(m.getPersonId());
                if (p == null) {
                    continue;
                }
                l = new Listed(p.getId(), null, null, p.getName(), p.isRetired(),
                        today != null && p.heldOn(today), m.covers(today));
            }
            BigDecimal share = m.getShare().setScale(2);
            listed.add(l);
            shares.add(share);
            if (l.current()) {
                total = total.add(share);
            }
        }
        return new Shares(listed, shares, total, total.compareTo(HUNDRED) == 0);
    }

    /**
     * A pool's cuts, in its members' order, or null for none: a pool that is a member of another cuts
     * its cut in that pool, one that is a member of none the sheet's pot; an unbalanced pool, one whose
     * every current member is retired, or no pot to cut, has none.
     */
    private static List<BigDecimal> cutsOf(long poolId, Map<Long, Shares> shares, Map<Long, Long> parentOf,
            BigDecimal sheetPot, Map<Long, List<BigDecimal>> memo, Set<Long> visiting) {
        if (memo.containsKey(poolId)) {
            return memo.get(poolId);
        }
        Shares sh = shares.get(poolId);
        BigDecimal pot = sheetPot;
        Long parent = parentOf.get(poolId);
        if (parent != null) {
            pot = null;
            if (visiting.add(poolId)) {
                List<BigDecimal> above = cutsOf(parent, shares, parentOf, sheetPot, memo, visiting);
                if (above != null) {
                    List<Listed> siblings = shares.get(parent).listed();
                    for (int i = 0; i < siblings.size(); i++) {
                        if (Long.valueOf(poolId).equals(siblings.get(i).memberPoolId())) {
                            pot = above.get(i);
                        }
                    }
                }
            }
        }
        boolean allRetired = sh.listed().stream().filter(Listed::current).allMatch(Listed::retired);
        List<BigDecimal> cuts = sh.balanced() && pot != null && !allRetired ? cuts(pot, sh.listed(), sh.shares()) : null;
        memo.put(poolId, cuts);
        return cuts;
    }
    /**
     * Each member's cut of the pot, in the members' order: the pot times their share over the shares of
     * the members with a cut added up, half to even, a retired or held member's 0.00; the remainder to
     * the member with the smallest non-zero cut, the latest added on a tie (the last member with a cut
     * when no cut is non-zero). A member whose dates leave
     * out today has none (null).
     */
    private static List<BigDecimal> cuts(BigDecimal pot, List<Listed> listed, List<BigDecimal> shares) {
        BigDecimal cutShares = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < listed.size(); i++) {
            if (hasCut(listed.get(i))) {
                cutShares = cutShares.add(shares.get(i));
            }
        }
        List<BigDecimal> cuts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int smallest = -1;
        int last = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (!listed.get(i).current()) {
                cuts.add(null);
                continue;
            }
            BigDecimal cut = BigDecimal.ZERO.setScale(2);
            if (hasCut(listed.get(i))) {
                cut = pot.multiply(shares.get(i)).divide(cutShares, 2, RoundingMode.HALF_EVEN);
                last = i;
                if (cut.signum() != 0 && (smallest < 0 || cut.compareTo(cuts.get(smallest)) <= 0)) {
                    smallest = i;
                }
            }
            cuts.add(cut);
            sum = sum.add(cut);
        }
        int to = smallest >= 0 ? smallest : last;
        if (to >= 0) {
            cuts.set(to, cuts.get(to).add(pot.subtract(sum)));
        }
        return cuts;
    }

    /** Whether a member takes a cut: within their dates, neither retired nor on a hold in force today. */
    private static boolean hasCut(Listed l) {
        return l.current() && !l.retired() && !l.held();
    }

    /** Add a pool (trimmed); a blank name is refused (422), one already on the sheet as a pool or a crew (409). */
    @Transactional
    public void add(String sheetId, String user, String name) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter a pool name.");
        }
        // hold the sheet so the same name added twice at the same moment is checked one after the other
        sheetRows.findLockedById(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet.");
        }
        if (crews.existsBySheetIdAndName(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A crew on the sheet has that name.");
        }
        try {
            pools.saveAndFlush(new Pool(sheetId, text));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already on the sheet.");
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + text);
    }

    /**
     * Add a person to a pool with a share: refused (422) for a share that is not a number of at most
     * two decimals above 0.00 and at most 100.00 or a to-date before the from-date, (409) for a person
     * already in the pool.
     */
    @Transactional
    public void addMember(String sheetId, String user, long poolId, long personId, String share, LocalDate from,
            LocalDate to) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        BigDecimal pct = share(share);
        dates(from, to);
        sheetRows.findLockedById(sheetId);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a person."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        try {
            members.saveAndFlush(new PoolMember(sheetId, pool.getId(), person.getId(), pct).during(from, to));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool.");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + pct.toPlainString() + dated(from, to));
    }

    /**
     * Add another pool to a pool with a share and dates, refused (422) for a share or dates as for a
     * person; (409) for a
     * pool already a member of a pool, for the pool itself or one that contains it through any chain,
     * and for a chain of pools that would be more than three deep.
     */
    @Transactional
    public void addPoolMember(String sheetId, String user, long poolId, long memberPoolId, String share,
            LocalDate from, LocalDate to) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        BigDecimal pct = share(share);
        dates(from, to);
        sheetRows.findLockedById(sheetId);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Pool member = pools.findByIdAndSheetId(memberPoolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a member."));
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> parentOf = new HashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (PoolMember m : members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())) {
            if (m.getMemberPoolId() != null) {
                parentOf.put(m.getMemberPoolId(), m.getPoolId());
                childrenOf.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
            }
        }
        if (parentOf.containsKey(member.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool.");
        }
        // the pool and the pools above it: the member may be none of them
        int above = 0;
        for (Long at = pool.getId(); at != null && above <= all.size(); at = parentOf.get(at)) {
            if (at.equals(member.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool cannot contain itself.");
            }
            above++;
        }
        if (above + height(member.getId(), childrenOf, 0) > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A chain of pools is at most " + MAX_DEPTH + " deep.");
        }
        try {
            members.saveAndFlush(PoolMember.ofPool(sheetId, pool.getId(), member.getId(), pct).during(from, to));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That pool is already a member of a pool.");
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + member.getName() + " share=" + pct.toPlainString() + dated(from, to));
    }

    /** How many levels of pools a pool heads: 1 for one with no pool members. */
    private static int height(Long poolId, Map<Long, List<Long>> childrenOf, int seen) {
        int most = 0;
        if (seen <= MAX_DEPTH) {
            for (Long child : childrenOf.getOrDefault(poolId, List.of())) {
                most = Math.max(most, height(child, childrenOf, seen + 1));
            }
        }
        return 1 + most;
    }

    /** A to-date before the from-date is refused (422). */
    private static void dates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The to-date may not be before the from-date.");
        }
    }

    /** The audit fields for a membership's dates: from= and to= when given. */
    private static String dated(LocalDate from, LocalDate to) {
        return (from != null ? " from=" + from : "") + (to != null ? " to=" + to : "");
    }

    private static BigDecimal share(String text) {
        String s = text == null ? "" : text.trim();
        if (!s.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Enter a share of at most two decimals.");
        }
        BigDecimal pct = new BigDecimal(s).setScale(2);
        if (pct.signum() <= 0 || pct.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A share is more than 0.00 and at most 100.00.");
        }
        return pct;
    }
}
