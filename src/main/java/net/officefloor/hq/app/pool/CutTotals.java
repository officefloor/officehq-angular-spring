package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewMember;
import net.officefloor.hq.app.crew.CrewMemberRepository;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.person.Person;
import org.springframework.stereotype.Service;

/**
 * Each person's cut total, as the pools show their cuts: their cuts across every pool (and crew, a
 * crew being a pool of its lead and members at equal shares) they are a member of, added up. A pool
 * that is a member of none cuts the sheet's pot; a pool that is a member of another has no pot of
 * its own but its cut in the pool above. Each pool's cuts are worked as the pools screen works them.
 */
@Service
public class CutTotals {

    /** A member of a pool as the cuts take it: the person or the member pool's name (exactly one),
     *  its share and its dates (either null for none). */
    record Member(Long personId, String pool, BigDecimal share, LocalDate from, LocalDate to) {
        boolean on(LocalDate day) {
            return day == null || ((from == null || !from.isAfter(day)) && (to == null || !day.isAfter(to)));
        }

        String key() {
            return pool != null ? "pool-" + pool : String.valueOf(personId);
        }
    }

    /** A pool as the cuts take it: its name and its members, in the order they were added. */
    record CutPool(String name, List<Member> members) {
    }

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final CrewRepository crews;
    private final CrewMemberRepository crewMembers;

    public CutTotals(PoolRepository pools, PoolMemberRepository members, CrewRepository crews,
            CrewMemberRepository crewMembers) {
        this.pools = pools;
        this.members = members;
        this.crews = crews;
        this.crewMembers = crewMembers;
    }

    /** Each person's cut total on the sheet now, by person id, on the day with the pot and the
     *  people (by id) as given. */
    public Map<Long, BigDecimal> of(String sheetId, BigDecimal pot, Map<Long, Person> people, LocalDate day) {
        if (pot == null) {
            return Map.of();
        }
        List<Pool> sheetPools = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, String> poolNames = sheetPools.stream().collect(Collectors.toMap(Pool::getId, Pool::getName));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(poolNames.keySet()).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        List<CutPool> all = new ArrayList<>();
        for (Pool p : sheetPools) {
            all.add(new CutPool(p.getName(), byPool.getOrDefault(p.getId(), List.of()).stream()
                    .map(m -> new Member(m.getPersonId(), m.getMemberPoolId() == null ? null : poolNames.get(m.getMemberPoolId()),
                            m.getShare(), m.getFromDate(), m.getToDate()))
                    .toList()));
        }
        List<Crew> sheetCrews = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<Long>> byCrew = new HashMap<>();
        crewMembers.findByCrewIdInOrderByIdAsc(sheetCrews.stream().map(Crew::getId).toList())
                .forEach(m -> byCrew.computeIfAbsent(m.getCrewId(), k -> new ArrayList<>()).add(m.getPersonId()));
        for (Crew c : sheetCrews) {
            all.add(crewPool(c.getName(), c.getLeadId(), byCrew.getOrDefault(c.getId(), List.of())));
        }
        return totals(all, pot, people, day);
    }

    /** Each person's cut total on the sheet as it stood then, by person id, on its today then. */
    public static Map<Long, BigDecimal> of(HistoryService.AsOf then) {
        BigDecimal pot = then.sheet().getPot();
        if (pot == null) {
            return Map.of();
        }
        List<CutPool> all = new ArrayList<>();
        then.pools().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> all.add(new CutPool(
                e.getValue().name(), then.poolMembers().entrySet().stream()
                        .filter(m -> m.getValue().poolId() == e.getKey())
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .map(m -> new Member(m.personId(),
                                m.memberPoolId() == null || !then.pools().containsKey(m.memberPoolId()) ? null
                                        : then.pools().get(m.memberPoolId()).name(),
                                m.share(), m.from(), m.to()))
                        .toList())));
        then.crews().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> all.add(crewPool(
                e.getValue().name(), e.getValue().leadId(), then.crewMembers().entrySet().stream()
                        .filter(m -> m.getValue().crewId() == e.getKey())
                        .sorted(Map.Entry.comparingByKey())
                        .map(m -> m.getValue().personId())
                        .toList())));
        return totals(all, pot, then.people(), then.sheet().getToday());
    }

    /** A crew as a pool under its name: its lead, then its members, at equal shares (100.00 split
     *  half to even, the remainder on the first). */
    private static CutPool crewPool(String name, Long leadId, List<Long> memberIds) {
        List<Long> ids = new ArrayList<>();
        if (leadId != null) {
            ids.add(leadId);
        }
        ids.addAll(memberIds);
        List<Member> out = new ArrayList<>();
        if (!ids.isEmpty()) {
            long share = BigDecimal.valueOf(10000).divide(BigDecimal.valueOf(ids.size()), 0, RoundingMode.HALF_EVEN)
                    .longValueExact();
            for (int i = 0; i < ids.size(); i++) {
                long cents = i == 0 ? share + 10000 - share * ids.size() : share;
                out.add(new Member(ids.get(i), null, BigDecimal.valueOf(cents, 2), null, null));
            }
        }
        return new CutPool(name, out);
    }

    /** The cuts of every pool added up per person: a person retired (any retirement date) or held on
     *  the day gets none, their share spread over the others. */
    private static Map<Long, BigDecimal> totals(List<CutPool> all, BigDecimal pot, Map<Long, Person> people,
            LocalDate day) {
        Set<Long> retired = new HashSet<>();
        Set<Long> held = new HashSet<>();
        people.forEach((id, p) -> {
            if (p.isRetired()) {
                retired.add(id);
            }
            if (day != null && p.heldOn(day)) {
                held.add(id);
            }
        });
        Map<String, String> parentOf = new HashMap<>();
        for (CutPool p : all) {
            for (Member m : p.members()) {
                if (m.pool() != null) {
                    parentOf.put(m.pool(), p.name());
                }
            }
        }
        Map<String, CutPool> byName = new HashMap<>();
        all.forEach(p -> byName.putIfAbsent(p.name(), p));
        Map<String, Map<String, Long>> cuts = new HashMap<>();
        long potCents = cents(pot);
        for (CutPool p : all) {
            cutsFor(p, Set.of(p.name()), potCents, parentOf, byName, cuts, retired::contains, held::contains, day);
        }
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (CutPool p : all) {
            Map<String, Long> poolCuts = cuts.get(p.name());
            for (Member m : p.members()) {
                Long cut = poolCuts == null ? null : poolCuts.get(m.key());
                if (m.personId() != null && cut != null) {
                    totals.merge(m.personId(), cut, Long::sum);
                }
            }
        }
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        totals.forEach((id, c) -> out.put(id, BigDecimal.valueOf(c, 2)));
        return out;
    }

    private static Map<String, Long> cutsFor(CutPool pool, Set<String> seen, long pot, Map<String, String> parentOf,
            Map<String, CutPool> byName, Map<String, Map<String, Long>> all, Predicate<Long> retired,
            Predicate<Long> held, LocalDate day) {
        Map<String, Long> known = all.get(pool.name());
        if (known != null) {
            return known;
        }
        String parent = parentOf.get(pool.name());
        Long potHere = pot;
        if (parent != null) {
            CutPool above = byName.get(parent);
            Set<String> deeper = new HashSet<>(seen);
            deeper.add(pool.name());
            potHere = above != null && !seen.contains(parent)
                    ? cutsFor(above, deeper, pot, parentOf, byName, all, retired, held, day).get("pool-" + pool.name())
                    : null;
        }
        Map<String, Long> cuts = cutsOf(pool, potHere, retired, held, day);
        all.put(pool.name(), cuts);
        return cuts;
    }

    /** Each member's cut of the pool's pot in cents, by member key: the pot times their share over
     *  the shares of the members with a cut, rounded half to even, the remainder on the member with
     *  the smallest non-zero cut (the latest added on a tie; else the last member with a share). A
     *  retired or held member gets 0; none unless the pool is balanced and has a pot; a member whose
     *  dates do not include the day has no cut at all. */
    private static Map<String, Long> cutsOf(CutPool pool, Long pot, Predicate<Long> retired, Predicate<Long> held,
            LocalDate day) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<Member> ms = pool.members();
        long[] shares = ms.stream().mapToLong(m -> m.on(day) ? cents(m.share()) : 0).toArray();
        if (pot == null || Arrays.stream(shares).sum() != 10000) {
            return out;
        }
        if (ms.stream().filter(m -> m.on(day)).allMatch(m -> m.personId() != null && retired.test(m.personId()))) {
            return out;
        }
        long[] cutShares = new long[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            Long id = ms.get(i).personId();
            cutShares[i] = id != null && (retired.test(id) || held.test(id)) ? 0 : shares[i];
        }
        long total = Arrays.stream(cutShares).sum();
        long[] raw = new long[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            raw[i] = cutShares[i] == 0 ? 0 : shareOf(pot, cutShares[i], total);
        }
        if (total > 0) {
            int target = -1;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] != 0 && (target < 0 || Math.abs(raw[i]) <= Math.abs(raw[target]))) {
                    target = i;
                }
            }
            if (target < 0) {
                for (int i = 0; i < cutShares.length; i++) {
                    if (cutShares[i] > 0) {
                        target = i;
                    }
                }
            }
            raw[target] += pot - Arrays.stream(raw).sum();
        }
        for (int i = 0; i < ms.size(); i++) {
            if (ms.get(i).on(day)) {
                out.put(ms.get(i).key(), raw[i]);
            }
        }
        return out;
    }

    /** Whole cents times a share over the shares with a cut, rounded half to even (a negative
     *  product rounded as its size is). */
    private static long shareOf(long cents, long share, long total) {
        BigDecimal product = BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(share));
        BigDecimal size = product.abs().divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_EVEN);
        return product.signum() < 0 ? -size.longValueExact() : size.longValueExact();
    }

    private static long cents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
