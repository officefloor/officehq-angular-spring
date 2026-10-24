package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pools on a sheet and their members: people, or other pools. A pool's total share adds up its members'
 * shares, a retired person's (one with a retirement date set, whatever the date) counting as 0.00; the pool is
 * balanced when the total is exactly 100.00. A pool that is a member of none has the sheet's pot; a pool that is
 * a member of another has its cut in that pool as its pot (none when that pool has no cuts). Each member of a
 * balanced pool with a pot cuts the pot times their share over 100, rounded half to even to two decimals (a
 * retired person 0.00), and the remainder of the pot over the cuts goes to the member with the largest share, the
 * earliest added on a tie, so the cuts add up to the pot exactly; an unbalanced pool, or one with no pot, has no
 * cuts. A pool is a member of at most one pool, never contains itself through any chain, and a chain of pools is
 * at most three deep. */
@Service
public class PoolService {

    /** A share as typed: a percent with at most two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** The most pools in a chain, the outermost counted. */
    private static final int DEPTH = 3;

    /** A member of a pool as shown: the person (or, for a pool, null), the pool (or, for a person, null), the
     * name now, the share and the cut (null when none). */
    public record Member(Long personId, Long poolId, String name, BigDecimal share, BigDecimal cut) {
    }

    /** A pool as shown: its members in the order they were added, its total share and whether it is balanced. */
    public record Pooled(SheetPool pool, List<Member> members, BigDecimal totalShare, boolean balanced) {
    }

    private final SheetRepository sheets;
    private final SheetPoolRepository pools;
    private final SheetPoolMemberRepository members;
    private final PersonRepository people;
    private final Audit audit;

    public PoolService(SheetRepository sheets, SheetPoolRepository pools, SheetPoolMemberRepository members,
            PersonRepository people, Audit audit) {
        this.sheets = sheets;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pooled> pools(String sheetId) {
        List<SheetPool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) return List.of();
        BigDecimal pot = sheets.findById(sheetId).map(Sheet::getPot).orElse(null);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, SheetPool> poolsById = all.stream().collect(Collectors.toMap(SheetPool::getId, Function.identity()));
        Map<Long, List<SheetPoolMember>> byPool = new LinkedHashMap<>();
        Map<Long, SheetPoolMember> memberOf = new HashMap<>();
        for (SheetPoolMember m : members.findByPoolIdInOrderByIdAsc(poolsById.keySet())) {
            if (m.getMemberPoolId() != null) {
                if (!poolsById.containsKey(m.getMemberPoolId())) continue;
                memberOf.put(m.getMemberPoolId(), m);
            } else if (!byId.containsKey(m.getPersonId())) {
                continue;
            }
            byPool.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m);
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (SheetPool pool : all) {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (SheetPoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                if (!retired(m, byId)) total = total.add(m.getShare());
            }
            totals.put(pool.getId(), total.setScale(2, RoundingMode.UNNECESSARY));
        }
        Map<Long, List<BigDecimal>> cutsByPool = new HashMap<>();
        List<Pooled> found = new ArrayList<>();
        for (SheetPool pool : all) {
            List<SheetPoolMember> kept = byPool.getOrDefault(pool.getId(), List.of());
            BigDecimal total = totals.get(pool.getId());
            List<BigDecimal> cuts = cutsOf(pool.getId(), pot, byPool, memberOf, totals, byId, cutsByPool);
            List<Member> shown = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                SheetPoolMember m = kept.get(i);
                BigDecimal cut = cuts == null ? null : cuts.get(i);
                if (m.getMemberPoolId() != null) {
                    shown.add(new Member(null, m.getMemberPoolId(), poolsById.get(m.getMemberPoolId()).getName(),
                            m.getShare().setScale(2), cut));
                } else {
                    Person person = byId.get(m.getPersonId());
                    shown.add(new Member(person.getId(), null, person.getName(), m.getShare().setScale(2), cut));
                }
            }
            found.add(new Pooled(pool, shown, total, total.compareTo(HUNDRED) == 0));
        }
        return found;
    }

    /** The cuts of a pool's members, in their order, or null when it is unbalanced or has no pot: its pot is the
     * sheet's when it is a member of no pool, else its cut in the pool it is a member of. */
    private static List<BigDecimal> cutsOf(long poolId, BigDecimal sheetPot, Map<Long, List<SheetPoolMember>> byPool,
            Map<Long, SheetPoolMember> memberOf, Map<Long, BigDecimal> totals, Map<Long, Person> byId,
            Map<Long, List<BigDecimal>> cutsByPool) {
        if (cutsByPool.containsKey(poolId)) return cutsByPool.get(poolId);
        cutsByPool.put(poolId, null);
        BigDecimal pot = sheetPot;
        SheetPoolMember membership = memberOf.get(poolId);
        if (membership != null) {
            List<BigDecimal> above = cutsOf(membership.getPoolId(), sheetPot, byPool, memberOf, totals, byId,
                    cutsByPool);
            pot = above == null ? null : above.get(byPool.get(membership.getPoolId()).indexOf(membership));
        }
        List<BigDecimal> cuts = pot != null && totals.get(poolId).compareTo(HUNDRED) == 0
                ? cuts(pot, byPool.getOrDefault(poolId, List.of()), byId)
                : null;
        cutsByPool.put(poolId, cuts);
        return cuts;
    }

    private static boolean retired(SheetPoolMember m, Map<Long, Person> byId) {
        return m.getPersonId() != null && byId.get(m.getPersonId()).isRetired();
    }

    /** The cuts of a balanced pool's members, in their order: the pot times each share over 100, half to even to
     * two decimals (a retired person's 0.00), the remainder going to the largest share, the earliest added on a tie. */
    private static List<BigDecimal> cuts(BigDecimal pot, List<SheetPoolMember> kept, Map<Long, Person> byId) {
        List<BigDecimal> cuts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int largest = -1;
        for (int i = 0; i < kept.size(); i++) {
            SheetPoolMember m = kept.get(i);
            if (retired(m, byId)) {
                cuts.add(BigDecimal.ZERO.setScale(2));
                continue;
            }
            BigDecimal cut = pot.multiply(m.getShare()).divide(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
            cuts.add(cut);
            sum = sum.add(cut);
            if (largest < 0 || m.getShare().compareTo(kept.get(largest).getShare()) > 0) largest = i;
        }
        if (largest >= 0) cuts.set(largest, cuts.get(largest).add(pot.setScale(2).subtract(sum)));
        return cuts;
    }

    /** Add a pool named {@code name} (trimmed, not blank); refused when the sheet has one by that name. */
    @Transactional
    public SheetPool add(String sheetId, String by, String name) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (name.isEmpty()) throw new PoolBlankException();
        if (pools.existsBySheetIdAndName(sheetId, name)) throw new PoolTakenException();
        SheetPool added = pools.saveAndFlush(new SheetPool(sheetId, name));
        audit.change("POOL_ADDED", sheetId, by, "name=" + name);
        return added;
    }

    /** Add the person to the pool with the share (as typed); refused when the person is already in the pool
     * or the share is not more than 0.00 and at most 100.00 with at most two decimals. */
    @Transactional
    public SheetPoolMember addMember(String sheetId, String by, long poolId, long personId, String share) {
        SheetPool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        BigDecimal amount = shareOf(share);
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) throw new PoolMemberTakenException();
        SheetPoolMember added = members.saveAndFlush(new SheetPoolMember(pool.getId(), person.getId(), amount));
        audit.change("POOL_MEMBER_ADDED", sheetId, by, "pool=" + pool.getName(), "name=" + person.getName(),
                "share=" + amount.toPlainString());
        return added;
    }

    /** Add the pool {@code memberPoolId} to the pool with the share (as typed); refused when it is already a
     * member of a pool, when it is the pool or contains it through any chain, when the chain of pools would be
     * more than three deep, or for a bad share. */
    @Transactional
    public SheetPoolMember addPoolMember(String sheetId, String by, long poolId, long memberPoolId, String share) {
        SheetPool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        SheetPool member = pools.findByIdAndSheetId(memberPoolId, sheetId).orElseThrow(PoolNotFoundException::new);
        BigDecimal amount = shareOf(share);
        Map<Long, Long> above = new HashMap<>();
        Map<Long, List<Long>> below = new HashMap<>();
        List<Long> ids = pools.findBySheetIdOrderByIdAsc(sheetId).stream().map(SheetPool::getId).toList();
        for (SheetPoolMember m : members.findByPoolIdInOrderByIdAsc(ids)) {
            if (m.getMemberPoolId() == null) continue;
            above.put(m.getMemberPoolId(), m.getPoolId());
            below.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m.getMemberPoolId());
        }
        int depth = 1;
        for (Long at = pool.getId(); at != null; at = above.get(at), depth++) {
            if (at.equals(member.getId())) throw new PoolCycleException();
        }
        if (above.containsKey(member.getId())) throw new PoolNestedException();
        if (depth - 1 + height(member.getId(), below) > DEPTH) throw new PoolDepthException();
        SheetPoolMember added = members.saveAndFlush(SheetPoolMember.ofPool(pool.getId(), member.getId(), amount));
        audit.change("POOL_MEMBER_ADDED", sheetId, by, "pool=" + pool.getName(), "name=" + member.getName(),
                "share=" + amount.toPlainString());
        return added;
    }

    /** How many pools deep the chain below a pool goes, the pool counted. */
    private static int height(long poolId, Map<Long, List<Long>> below) {
        int most = 0;
        for (Long child : below.getOrDefault(poolId, List.of())) most = Math.max(most, height(child, below));
        return most + 1;
    }

    private static BigDecimal shareOf(String share) {
        String typed = share == null ? "" : share.trim();
        if (!SHARE.matcher(typed).matches()) throw new PoolShareException();
        BigDecimal amount = new BigDecimal(typed).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(HUNDRED) > 0) throw new PoolShareException();
        return amount;
    }
}
