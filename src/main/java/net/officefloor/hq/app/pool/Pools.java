package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pools on a sheet, sorted by name, and their members: people of the sheet, each in a pool at most once, or
 * other pools of the sheet, each a member of at most one pool, with a share percent. A pool cannot contain itself
 * through any chain of pools, and a chain is at most three pools deep. A pool's total share counts a retired member's
 * share (a retirement date set, whatever the date) as 0.00; the pool is balanced when its total is exactly 100.00.
 * A pool that is a member of none has the sheet's pot; a pool that is a member of another has as its pot its cut in
 * that pool (none when that pool has no cuts). Each member of a balanced pool with a pot has a cut: 0.00 for a
 * retired member or a person with a hold covering the sheet's today, otherwise the pot times their share divided by
 * the shares of the members with a cut added up, rounded half to even to two decimals; the remainder, the pot less
 * the cuts added up, goes to the member with a cut with the largest share, the earliest added on a tie, so the cuts
 * total exactly the pot (all 0.00 when no member has a cut). A hold changes neither the total share nor the balance.
 */
@Service
public class Pools {

    /** The longest a pool's name may be. */
    static final int NAME_MAX = 100;

    /** The most pools a chain of pools, each a member of the one above, may hold. */
    static final int DEPTH_MAX = 3;

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /**
     * A member of a pool as shown: the person (null for a pool), the pool (null for a person), the name, their
     * share, whether they are retired and their cut (null: none).
     */
    public record Member(Long personId, String pool, String name, BigDecimal share, boolean retired, BigDecimal cut) {
    }

    /** A pool as shown: its members in the order they were added, its total share and whether it is balanced. */
    public record Shown(String name, List<Member> members, BigDecimal total, boolean balanced) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public Pools(PoolRepository pools, PoolMemberRepository members, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, ApplicationEventPublisher events) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The pools on the sheet, sorted by name, each with its members and figures. */
    @Transactional(readOnly = true)
    public List<Shown> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByNameAsc(sheetId);
        Map<Long, Pool> poolById = all.stream().collect(Collectors.toMap(Pool::getId, Function.identity()));
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        Map<Long, Long> above = new HashMap<>();
        byPool.values().forEach(ms -> ms.stream().filter(m -> m.getMemberPoolId() != null)
                .forEach(m -> above.put(m.getMemberPoolId(), m.getPoolId())));
        BigDecimal pot = sheets.settings(sheetId).pot();
        LocalDate today = sheets.today(sheetId);
        Set<Long> held = byId.values().stream().filter(p -> p.heldOn(today)).map(Person::getId)
                .collect(Collectors.toSet());
        Map<Long, Shown> shown = new HashMap<>();
        for (Pool pool : all) {
            show(pool.getId(), poolById, byId, byPool, above, pot, held, shown, new HashSet<>());
        }
        return all.stream().map(p -> shown.get(p.getId())).toList();
    }

    /** The pool shown with its figures, the pool above it shown first since its cut there is this pool's pot. */
    private Shown show(long poolId, Map<Long, Pool> poolById, Map<Long, Person> byId,
            Map<Long, List<PoolMember>> byPool, Map<Long, Long> above, BigDecimal sheetPot, Set<Long> held,
            Map<Long, Shown> shown, Set<Long> visiting) {
        Shown done = shown.get(poolId);
        if (done != null) {
            return done;
        }
        Pool pool = poolById.get(poolId);
        BigDecimal pot = sheetPot;
        Long parent = above.get(poolId);
        if (parent != null) {
            pot = null;
            if (poolById.containsKey(parent) && visiting.add(poolId)) {
                Shown up = show(parent, poolById, byId, byPool, above, sheetPot, held, shown, visiting);
                pot = up.members().stream().filter(m -> pool.getName().equals(m.pool())).map(Member::cut)
                        .filter(Objects::nonNull).findFirst().orElse(null);
            }
        }
        List<Member> listed = new ArrayList<>();
        for (PoolMember m : byPool.getOrDefault(poolId, List.of())) {
            if (m.getMemberPoolId() != null) {
                Pool member = poolById.get(m.getMemberPoolId());
                if (member != null) {
                    listed.add(new Member(null, member.getName(), member.getName(), m.getShare().setScale(2), false,
                            null));
                }
            } else if (byId.containsKey(m.getPersonId())) {
                Person p = byId.get(m.getPersonId());
                listed.add(new Member(p.getId(), null, p.getName(), m.getShare().setScale(2), p.isRetired(), null));
            }
        }
        BigDecimal total = listed.stream().filter(m -> !m.retired()).map(Member::share)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        boolean balanced = total.compareTo(HUNDRED) == 0;
        Shown result = new Shown(pool.getName(), balanced && pot != null ? cut(listed, pot, held) : listed, total, balanced);
        shown.put(poolId, result);
        return result;
    }

    /**
     * The members, in the order added, each with their cut of the pot spread over the members with a cut (not retired
     * nor held); the remainder goes to the largest share among them.
     */
    private static List<Member> cut(List<Member> shown, BigDecimal pot, Set<Long> held) {
        List<Boolean> cutting = shown.stream()
                .map(m -> !m.retired() && (m.personId() == null || !held.contains(m.personId()))).toList();
        BigDecimal shares = BigDecimal.ZERO;
        for (int i = 0; i < shown.size(); i++) {
            if (cutting.get(i)) {
                shares = shares.add(shown.get(i).share());
            }
        }
        List<BigDecimal> cuts = new ArrayList<>();
        int largest = -1;
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            cuts.add(!cutting.get(i) ? BigDecimal.ZERO.setScale(2)
                    : pot.multiply(m.share()).divide(shares, 2, RoundingMode.HALF_EVEN));
            if (cutting.get(i) && (largest < 0 || m.share().compareTo(shown.get(largest).share()) > 0)) {
                largest = i;
            }
        }
        if (largest >= 0) {
            BigDecimal sum = cuts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            cuts.set(largest, cuts.get(largest).add(pot.subtract(sum)).setScale(2));
        }
        List<Member> cut = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            cut.add(new Member(m.personId(), m.pool(), m.name(), m.share(), m.retired(), cuts.get(i)));
        }
        return cut;
    }

    /** Add a pool under its name, trimmed; refused when the name is blank, too long or already a pool's. */
    @Transactional
    public void add(String sheetId, String user, String text) {
        String name = text == null ? "" : text.strip();
        if (name.isEmpty()) {
            throw new PoolRefused("Enter a name for the pool.");
        }
        if (name.length() > NAME_MAX) {
            throw new PoolRefused("The pool's name is too long.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (pools.findBySheetIdAndName(sheetId, name).isPresent()) {
            throw new PoolRefused("There is already a pool named " + name + ".");
        }
        pools.saveAndFlush(new Pool(sheetId, name));
        events.publishEvent(new PoolAdded(sheetId, user, name));
    }

    /**
     * Add a person of the sheet to the named pool with a share of two decimals, more than 0.00 and at most 100.00;
     * refused when the person is in the pool already.
     */
    @Transactional
    public void addMember(String sheetId, String user, String poolName, long personId, String text) {
        BigDecimal share = share(text);
        sheetRows.lockById(sheetId);
        Pool pool = pool(sheetId, poolName);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PoolRefused("Choose a person on this sheet."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new PoolRefused(person.getName() + " is in the pool already.");
        }
        members.saveAndFlush(new PoolMember(pool.getId(), person.getId(), share));
        events.publishEvent(new PoolMemberAdded(sheetId, user, pool.getName(), person.getName(), share));
    }

    /**
     * Add another pool of the sheet to the named pool with a share like a person's; refused when the member is the
     * pool itself or contains it through any chain, is a member of a pool already, or the chain would be more than
     * three pools deep.
     */
    @Transactional
    public void addPoolMember(String sheetId, String user, String poolName, String memberName, String text) {
        BigDecimal share = share(text);
        sheetRows.lockById(sheetId);
        Pool pool = pool(sheetId, poolName);
        Pool member = pools.findBySheetIdAndName(sheetId, memberName == null ? "" : memberName.strip())
                .orElseThrow(() -> new PoolRefused("Choose a pool on this sheet."));
        List<Pool> all = pools.findBySheetIdOrderByNameAsc(sheetId);
        Map<Long, Long> above = new HashMap<>();
        Map<Long, List<Long>> below = new HashMap<>();
        members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList()).stream()
                .filter(m -> m.getMemberPoolId() != null).forEach(m -> {
                    above.put(m.getMemberPoolId(), m.getPoolId());
                    below.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
                });
        int levelsAbove = 1;
        boolean contains = member.getId().equals(pool.getId());
        Set<Long> seen = new HashSet<>(List.of(pool.getId()));
        for (Long up = above.get(pool.getId()); up != null && seen.add(up); up = above.get(up)) {
            levelsAbove++;
            contains |= up.equals(member.getId());
        }
        if (contains) {
            throw new PoolRefused("A pool cannot contain itself.");
        }
        if (above.containsKey(member.getId())) {
            throw new PoolRefused(member.getName() + " is a member of a pool already.");
        }
        if (levelsAbove + depth(member.getId(), below, new HashSet<>()) > DEPTH_MAX) {
            throw new PoolRefused("A chain of pools is at most " + DEPTH_MAX + " deep.");
        }
        members.saveAndFlush(PoolMember.ofPool(pool.getId(), member.getId(), share));
        events.publishEvent(new PoolMemberAdded(sheetId, user, pool.getName(), member.getName(), share));
    }

    /** How many pools deep the chain is from this pool down: 1 when no pool is a member of it. */
    private static int depth(long poolId, Map<Long, List<Long>> below, Set<Long> seen) {
        if (!seen.add(poolId)) {
            return 0;
        }
        int deepest = 0;
        for (long child : below.getOrDefault(poolId, List.of())) {
            deepest = Math.max(deepest, depth(child, below, seen));
        }
        return 1 + deepest;
    }

    private Pool pool(String sheetId, String poolName) {
        return pools.findBySheetIdAndName(sheetId, poolName == null ? "" : poolName.strip())
                .orElseThrow(() -> new PoolRefused("No such pool on this sheet."));
    }

    private static BigDecimal share(String text) {
        String s = text == null ? "" : text.strip();
        if (!s.matches("\\d{1,3}(\\.\\d{1,2})?")) {
            throw new PoolRefused("Enter a share with up to two decimals.");
        }
        BigDecimal share = new BigDecimal(s).setScale(2);
        if (share.signum() <= 0 || share.compareTo(HUNDRED) > 0) {
            throw new PoolRefused("A share is more than 0.00 and at most 100.00.");
        }
        return share;
    }
}
