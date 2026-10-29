package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.crew.CrewController.CrewView;
import net.officefloor.hq.app.pool.PoolController.MemberView;
import net.officefloor.hq.app.pool.PoolController.PoolView;
import net.officefloor.hq.app.pool.PoolHistoryController.PeriodPools;
import net.officefloor.hq.app.pool.PoolHistoryController.RosterView;
import org.springframework.stereotype.Service;

/**
 * Each person's cut total as the screen works it out: their cuts of the pots across every pool they are a member of
 * (each crew counting as a pool), for the pools as they stand now on the sheet's today.
 */
@Service
public class PoolCuts {

    private static final BigInteger HUNDRED_PERCENT = BigInteger.valueOf(10000);

    private final PoolStanding standing;

    public PoolCuts(PoolStanding standing) {
        this.standing = standing;
    }

    /** Each person's cut total, by person id; a person with no cut is absent. */
    public Map<Long, BigDecimal> totals(String sheetId, LocalDate today) {
        return totals(standing.now(sheetId, "", today, false));
    }

    /** A member as counted: its share in hundredths, and whether it has no cut or is left out. */
    private record Counted(Long personId, Long poolId, long share, boolean retired, boolean held, boolean out) {
    }

    /** Each person's cut total for the pools as they then stood, by person id; a person with no cut is absent. */
    public static Map<Long, BigDecimal> totals(PeriodPools standing) {
        LocalDate day = standing.day();
        Map<Long, Boolean> retired = new HashMap<>();
        Set<Long> held = new HashSet<>();
        for (RosterView p : standing.people()) {
            retired.put(p.id(), p.retiredFrom() != null);
            if (day != null && p.holds().stream().anyMatch(
                    h -> !h.from().isAfter(day) && (h.to() == null || !day.isAfter(h.to())))) {
                held.add(p.id());
            }
        }
        List<PoolView> pools = new ArrayList<>(standing.pools());
        for (CrewView crew : standing.crews()) {
            pools.add(crewPool(crew));
        }
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, List<Counted>> counted = new HashMap<>();
        Map<Long, Boolean> balanced = new HashMap<>();
        for (PoolView pool : pools) {
            List<Counted> members = new ArrayList<>();
            long total = 0;
            for (MemberView m : pool.members()) {
                if (m.poolId() != null) {
                    parent.put(m.poolId(), pool.id());
                }
                boolean out = day != null && ((m.from() != null && m.from().isAfter(day))
                        || (m.to() != null && day.isAfter(m.to())));
                long share = hundredths(m.share());
                members.add(new Counted(m.personId(), m.poolId(), share,
                        m.personId() != null && retired.getOrDefault(m.personId(), m.retired()),
                        m.personId() != null && held.contains(m.personId()), out));
                if (!out) {
                    total += share;
                }
            }
            counted.put(pool.id(), members);
            balanced.put(pool.id(), total == 10000);
        }
        Map<Long, BigInteger[]> cutsOf = new HashMap<>();
        Map<Long, BigInteger> sums = new HashMap<>();
        for (PoolView pool : pools) {
            BigInteger[] cut = poolCuts(pool.id(), standing.pot(), parent, counted, balanced, cutsOf);
            if (cut == null) {
                continue;
            }
            List<Counted> members = counted.get(pool.id());
            for (int i = 0; i < members.size(); i++) {
                Counted m = members.get(i);
                if (m.personId() != null && !m.out()) {
                    sums.merge(m.personId(), cut[i], BigInteger::add);
                }
            }
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        sums.forEach((id, c) -> totals.put(id, new BigDecimal(c, 2)));
        return totals;
    }

    /** The pool's cuts (in cents, in member order), or null for none; worked out top down. */
    private static BigInteger[] poolCuts(Long id, String sheetPot, Map<Long, Long> parent,
            Map<Long, List<Counted>> counted, Map<Long, Boolean> balanced, Map<Long, BigInteger[]> cutsOf) {
        if (cutsOf.containsKey(id)) {
            return cutsOf.get(id);
        }
        BigInteger pot = null;
        Long above = parent.get(id);
        if (above == null) {
            pot = sheetPot == null ? null : new BigDecimal(sheetPot).movePointRight(2).toBigIntegerExact();
        } else if (counted.containsKey(above)) {
            BigInteger[] aboveCuts = poolCuts(above, sheetPot, parent, counted, balanced, cutsOf);
            List<Counted> members = counted.get(above);
            for (int i = 0; i < members.size(); i++) {
                if (id.equals(members.get(i).poolId())) {
                    pot = aboveCuts == null ? null : aboveCuts[i];
                    break;
                }
            }
        }
        BigInteger[] result = balanced.getOrDefault(id, false) && pot != null ? cuts(pot, counted.get(id)) : null;
        cutsOf.put(id, result);
        return result;
    }

    /**
     * Each member's cut of the pot (in cents), in member order: the pot times their share over the shares of the
     * members with a cut added up, rounded half to even to the cent; a retired member, a held one, and one left out,
     * has none (0). The remainder left by the rounding goes to the member with the smallest non-zero cut (the latest
     * added on a tie), or, with none, to the last member with a share; a pool whose every member is retired has no
     * cuts at all (null).
     */
    private static BigInteger[] cuts(BigInteger pot, List<Counted> members) {
        if (!members.isEmpty() && members.stream().allMatch(Counted::retired)) {
            return null;
        }
        BigInteger[] shares = new BigInteger[members.size()];
        BigInteger of = BigInteger.ZERO;
        for (int i = 0; i < shares.length; i++) {
            Counted m = members.get(i);
            shares[i] = m.retired() || m.held() || m.out() ? BigInteger.ZERO : BigInteger.valueOf(m.share());
            of = of.add(shares[i]);
        }
        BigInteger[] result = new BigInteger[shares.length];
        if (of.signum() == 0) {
            java.util.Arrays.fill(result, BigInteger.ZERO);
            return result;
        }
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < shares.length; i++) {
            BigInteger[] qr = pot.multiply(shares[i]).divideAndRemainder(of);
            BigInteger q = qr[0];
            int r = qr[1].shiftLeft(1).compareTo(of);
            if (r > 0 || (r == 0 && q.testBit(0))) {
                q = q.add(BigInteger.ONE);
            }
            result[i] = q;
            sum = sum.add(q);
        }
        int to = -1;
        for (int i = 0; i < result.length; i++) {
            if (result[i].signum() != 0 && (to < 0 || result[i].compareTo(result[to]) <= 0)) {
                to = i;
            }
        }
        if (to < 0) {
            for (int i = 0; i < shares.length; i++) {
                if (shares[i].signum() > 0) {
                    to = i;
                }
            }
        }
        result[to] = result[to].add(pot.subtract(sum));
        return result;
    }

    /** A crew as a pool: its lead and members, in that order, at equal shares of 100.00, the remainder on the lead. */
    private static PoolView crewPool(CrewView crew) {
        List<Long> people = new ArrayList<>();
        if (crew.leadId() != null) {
            people.add(crew.leadId());
        }
        crew.members().forEach(m -> people.add(m.personId()));
        List<MemberView> members = new ArrayList<>();
        if (!people.isEmpty()) {
            BigInteger n = BigInteger.valueOf(people.size());
            BigInteger[] qr = HUNDRED_PERCENT.divideAndRemainder(n);
            BigInteger share = qr[0];
            int r = qr[1].shiftLeft(1).compareTo(n);
            if (r > 0 || (r == 0 && share.testBit(0))) {
                share = share.add(BigInteger.ONE);
            }
            BigInteger first = HUNDRED_PERCENT.subtract(share.multiply(n.subtract(BigInteger.ONE)));
            for (int i = 0; i < people.size(); i++) {
                members.add(new MemberView(people.get(i), null, "",
                        new BigDecimal(i == 0 ? first : share, 2).toPlainString(), false, null, null, true));
            }
        }
        return new PoolView(-crew.id(), crew.name(), members, "100.00", true);
    }

    private static long hundredths(String share) {
        return new BigDecimal(share).movePointRight(2).longValueExact();
    }
}
