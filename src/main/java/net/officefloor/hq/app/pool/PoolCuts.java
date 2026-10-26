package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.person.Person;

/**
 * Each pool's cuts as the pools screen works them out (see the front end's pools.ts): the sheet's pools and
 * then its crews as pools (under the crew's id negated, the lead and then the members at equal shares of
 * 100.00), each balanced pool sharing out its pot (the sheet's pot, or for a pool that is a member of
 * another its cut there) among the members whose dates include the sheet's today, a retired or held
 * person cutting 0.00 and their share spread over the others.
 */
public final class PoolCuts {

    private static final long FULL = 10000;

    /** A pool's member as the cuts take it: its key on the screens, share in cents, and whether it cuts. */
    private record Row(String key, long share, boolean inForce, boolean noCut) {
    }

    /** A pool or a crew as a pool: its id (a crew's negated), name and members. */
    private record AnyPool(long id, String name, List<Member> members) {
    }

    private record Member(Long personId, Long poolId, long share, LocalDate from, LocalDate to) {
    }

    private PoolCuts() {
    }

    /** Each pool's cuts with two decimals, by member key, by pool id (a crew's negated); a pool that shows no
     *  cuts (unbalanced, no pot, or every member retired or held) is left out. */
    public static Map<Long, Map<String, BigDecimal>> of(List<Pool> pools, List<Crew> crews, List<Person> people,
            BigDecimal pot, LocalDate today) {
        List<AnyPool> all = new ArrayList<>();
        for (Pool p : pools) {
            all.add(new AnyPool(p.getId(), p.getName(), p.getMembers().stream()
                    .map((m) -> new Member(m.getPersonId(), m.getMemberPoolId(), cents(m.getShare()), m.getFrom(), m.getTo()))
                    .toList()));
        }
        for (Crew c : crews) {
            List<Long> ids = new ArrayList<>();
            if (c.getLeadId() != null) ids.add(c.getLeadId());
            ids.addAll(c.getMemberIds());
            long count = ids.size();
            long each = count == 0 ? 0 : divideHalfEven(BigInteger.valueOf(FULL), BigInteger.valueOf(count)).longValue();
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                members.add(new Member(ids.get(i), null, i == 0 ? FULL - each * (count - 1) : each, null, null));
            }
            all.add(new AnyPool(-c.getId(), c.getName(), members));
        }
        Map<Long, AnyPool> byId = new HashMap<>();
        all.forEach((p) -> byId.put(p.id(), p));
        Map<Long, AnyPool> parents = new HashMap<>();
        for (AnyPool p : all) for (Member m : p.members()) if (m.poolId() != null) parents.put(m.poolId(), p);
        Map<Long, Person> peopleById = new HashMap<>();
        people.forEach((p) -> peopleById.put(p.getId(), p));

        Map<Long, Map<String, Long>> out = new HashMap<>();
        Map<Long, Boolean> done = new HashMap<>();
        for (AnyPool p : all) cutsFor(p, 0, all.size(), out, done, byId, parents, peopleById, pot, today);
        Map<Long, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (AnyPool p : all) {
            Map<String, Long> cuts = out.get(p.id());
            if (cuts == null) continue;
            Map<String, BigDecimal> money = new LinkedHashMap<>();
            cuts.forEach((key, cut) -> money.put(key, BigDecimal.valueOf(cut, 2)));
            result.put(p.id(), money);
        }
        return result;
    }

    private static Map<String, Long> cutsFor(AnyPool pool, int depth, int poolCount, Map<Long, Map<String, Long>> out,
            Map<Long, Boolean> done, Map<Long, AnyPool> byId, Map<Long, AnyPool> parents, Map<Long, Person> people,
            BigDecimal pot, LocalDate today) {
        if (done.containsKey(pool.id())) return out.get(pool.id());
        done.put(pool.id(), true);
        Long potCents = null;
        AnyPool parent = parents.get(pool.id());
        if (parent == null) {
            potCents = pot == null ? null : cents(pot);
        } else if (depth <= poolCount) {
            Map<String, Long> parentCuts = cutsFor(parent, depth + 1, poolCount, out, done, byId, parents, people, pot, today);
            potCents = parentCuts == null ? null : parentCuts.get("pool-" + pool.name());
        }
        List<Row> rows = new ArrayList<>();
        long total = 0;
        for (Member m : pool.members()) {
            Person person = m.personId() != null ? people.get(m.personId()) : null;
            String key = m.personId() != null ? String.valueOf(m.personId())
                    : "pool-" + (byId.containsKey(m.poolId()) ? byId.get(m.poolId()).name() : m.poolId());
            boolean inForce = (m.from() == null || !m.from().isAfter(today)) && (m.to() == null || !today.isAfter(m.to()));
            boolean noCut = person != null && (person.isRetired() || person.isHeldOn(today));
            if (inForce) total += m.share();
            rows.add(new Row(key, m.share(), inForce, noCut));
        }
        List<Row> inForce = rows.stream().filter(Row::inForce).toList();
        if (potCents == null || total != FULL || inForce.stream().allMatch(Row::noCut)) return null;
        Map<String, Long> cuts = cutsOf(potCents, inForce);
        out.put(pool.id(), cuts);
        return cuts;
    }

    /** The cuts of a pot (in cents) among the members, as the front end's cutsOf shares it. */
    private static Map<String, Long> cutsOf(long potCents, List<Row> members) {
        long[] shares = members.stream().mapToLong((m) -> m.noCut() ? 0 : m.share()).toArray();
        long total = 0;
        for (long s : shares) total += s;
        long[] cuts = new long[shares.length];
        for (int i = 0; i < shares.length; i++) {
            cuts[i] = total == 0 ? 0 : divideHalfEven(BigInteger.valueOf(potCents).multiply(BigInteger.valueOf(shares[i])),
                    BigInteger.valueOf(total)).longValue();
        }
        int target = -1;
        for (int i = 0; i < cuts.length; i++) {
            if (cuts[i] != 0 && (target < 0 || cuts[i] <= cuts[target])) target = i;
        }
        if (target < 0) {
            for (int i = 0; i < shares.length; i++) if (shares[i] > 0) target = i;
        }
        if (total > 0 && target >= 0) {
            long sum = 0;
            for (long c : cuts) sum += c;
            cuts[target] += potCents - sum;
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) out.put(members.get(i).key(), cuts[i]);
        return out;
    }

    private static long cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    /** A whole number divided by another, rounded half to even. */
    private static BigInteger divideHalfEven(BigInteger value, BigInteger by) {
        return new BigDecimal(value).divide(new BigDecimal(by), 0, RoundingMode.HALF_EVEN).toBigInteger();
    }
}
