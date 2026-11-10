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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's pools: each a name, at most once on the sheet, and its members, each a person on the
 * sheet at most once per pool with a share percent (two decimals, above 0.00 and at most 100.00).
 * A pool's total adds up its members' shares, a retired member's (one with a retirement date set,
 * whatever the date) counting as 0.00; the pool is balanced when the total is exactly 100.00.
 * <p>
 * With a pot set on the sheet, each member of a balanced pool has a cut: the pot times their share
 * divided by 100, rounded half to even to two decimals, a retired member's 0.00. The remainder, the
 * pot less the cuts added up, goes to the member with the largest share (the earliest added on a
 * tie), so the cuts total exactly the pot. An unbalanced pool, or no pot, has no cuts.
 * <p>
 * A person with a hold in force on the sheet's today (one whose dates include today) has a cut of
 * 0.00 in every pool, and their share is spread over the members left with a cut: each of those
 * cuts the pot times their share divided by the shares of the members with a cut added up, half to
 * even, the remainder as above (all 0.00 when no member is left with a cut). The pool's total share
 * and balance are unchanged by a hold.
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
    public record MemberView(long personId, String pool, String name, String share, boolean retired, String cut) {
    }

    /** A pool as listed: its members in the order they were added, their total share (two decimals) and balance. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetRows, Audit audit, RoleService roles) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<PoolView> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, Pool> poolsById = all.stream().collect(Collectors.toMap(Pool::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members
                .findByPoolIdInOrderByIdAsc(poolsById.keySet()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
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
                        l.retired(), poolCuts == null || poolCuts.get(i) == null ? "" : poolCuts.get(i).toPlainString()));
            }
            out.add(new PoolView(pool.getId(), pool.getName(), views, sh.total().toPlainString(), sh.balanced()));
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
            if (!l.retired() && l.current()) {
                total = total.add(share);
            }
        }
        return new Shares(listed, shares, total, total.compareTo(HUNDRED) == 0);
    }

    /**
     * A pool's cuts, in its members' order, or null for none: a pool that is a member of another cuts
     * its cut in that pool, one that is a member of none the sheet's pot; an unbalanced pool, or no pot
     * to cut, has none.
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
        List<BigDecimal> cuts = sh.balanced() && pot != null ? cuts(pot, sh.listed(), sh.shares()) : null;
        memo.put(poolId, cuts);
        return cuts;
    }
    /**
     * Each member's cut of the pot, in the members' order: the pot times their share over the shares of
     * the members with a cut added up, half to even, a retired or held member's 0.00; the remainder to
     * the member with a cut with the largest share, the earliest on a tie. A member whose dates leave
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
        int largest = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (!listed.get(i).current()) {
                cuts.add(null);
                continue;
            }
            BigDecimal cut = BigDecimal.ZERO.setScale(2);
            if (hasCut(listed.get(i))) {
                cut = pot.multiply(shares.get(i)).divide(cutShares, 2, RoundingMode.HALF_EVEN);
                if (largest < 0 || shares.get(i).compareTo(shares.get(largest)) > 0) {
                    largest = i;
                }
            }
            cuts.add(cut);
            sum = sum.add(cut);
        }
        if (largest >= 0) {
            cuts.set(largest, cuts.get(largest).add(pot.subtract(sum)));
        }
        return cuts;
    }

    /** Whether a member takes a cut: within their dates, neither retired nor on a hold in force today. */
    private static boolean hasCut(Listed l) {
        return l.current() && !l.retired() && !l.held();
    }

    /** Add a pool (trimmed); a blank name is refused (422), one already on the sheet (409). */
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
