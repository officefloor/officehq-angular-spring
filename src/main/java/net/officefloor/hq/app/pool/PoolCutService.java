package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out each pool member's cut of the pot as the sheet stands now, with its today, as the pools screen shows it:
 * in a balanced pool (a member whose dates leave out the today counting as 0.00) with a pot, each member's cut is the
 * pot times their share divided by the shares of the members with a cut added up, rounded half to even; a member
 * retired, or on hold on the today, gets none, their share spread over the others; the remainder goes to the member
 * with the smallest non-zero cut (the latest added on a tie), or with none to the last member with a cut and a share.
 * A pool that is a member of none has the sheet's pot; one that is a member of another has its cut there as its pot. A
 * crew is a pool too: its lead and members at the shares patrons get.
 */
@Service
public class PoolCutService {

    private static final long WHOLE = 10000;

    /** A member as the cuts need it: the person or the pool, the share in hundredths and the membership's dates. */
    private record Member(Long personId, Long poolId, long share, LocalDate from, LocalDate to) {

        String key() {
            return poolId != null ? PoolCut.pool(poolId) : PoolCut.person(personId);
        }

        boolean dated(LocalDate today) {
            return today == null || ((from == null || !from.isAfter(today)) && (to == null || !today.isAfter(to)));
        }
    }

    private record Group(Long id, List<Member> members) {
    }

    private final PoolRepository pools;
    private final CrewRepository crews;
    private final PersonRepository people;
    private final SheetRepository sheets;

    public PoolCutService(PoolRepository pools, CrewRepository crews, PersonRepository people, SheetRepository sheets) {
        this.pools = pools;
        this.crews = crews;
        this.people = people;
        this.sheets = sheets;
    }

    /** Every member's cut in every pool that has cuts, as the sheet stands now; a member with none is left out. */
    @Transactional(readOnly = true)
    public List<PoolCut> cutsNow(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        if (sheet == null || sheet.getPot() == null) {
            return List.of();
        }
        LocalDate today = sheet.getToday();
        long pot = sheet.getPot().movePointRight(2).longValueExact();
        List<Group> groups = new ArrayList<>();
        for (Pool p : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            groups.add(new Group(p.getId(), p.getMembers().stream()
                    .map(m -> new Member(m.getPersonId(), m.getMemberPoolId(), cents(m.getShare()), m.getFromDate(),
                            m.getToDate()))
                    .toList()));
        }
        for (Crew c : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Long> ids = new ArrayList<>();
            if (c.getLeadId() != null) {
                ids.add(c.getLeadId());
            }
            c.getMembers().forEach(m -> ids.add(m.getPersonId()));
            List<BigDecimal> shares = PersonService.patronShares(ids.size());
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                members.add(new Member(ids.get(i), null, cents(shares.get(i)), null, null));
            }
            groups.add(new Group(-c.getId(), members));
        }
        Map<Long, Person> byId = new HashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> byId.put(p.getId(), p));
        Map<Long, Group> groupsById = new HashMap<>();
        Map<Long, Long> above = new HashMap<>();
        for (Group g : groups) {
            groupsById.put(g.id(), g);
            for (Member m : g.members()) {
                if (m.poolId() != null) {
                    above.put(m.poolId(), g.id());
                }
            }
        }
        Map<Long, long[]> cuts = new HashMap<>();
        List<PoolCut> out = new ArrayList<>();
        for (Group g : groups) {
            long[] gc = cutsOf(g, groupsById, above, byId, pot, today, cuts, 0);
            if (gc == null) {
                continue;
            }
            for (int i = 0; i < g.members().size(); i++) {
                Member m = g.members().get(i);
                if (m.dated(today)) {
                    out.add(new PoolCut(g.id(), m.key(), BigDecimal.valueOf(gc[i], 2)));
                }
            }
        }
        return out;
    }

    /** The pool's cuts (in cents, in member order); null when it shows none. */
    private long[] cutsOf(Group g, Map<Long, Group> groupsById, Map<Long, Long> above, Map<Long, Person> byId,
            long sheetPot, LocalDate today, Map<Long, long[]> known, int depth) {
        if (known.containsKey(g.id())) {
            return known.get(g.id());
        }
        List<Member> members = g.members();
        int n = members.size();
        boolean[] outside = new boolean[n];
        boolean[] retired = new boolean[n];
        boolean[] held = new boolean[n];
        long total = 0;
        boolean anyInside = false;
        boolean allRetired = true;
        for (int i = 0; i < n; i++) {
            Member m = members.get(i);
            outside[i] = !m.dated(today);
            Person person = m.personId() != null ? byId.get(m.personId()) : null;
            retired[i] = person != null && person.isRetired();
            held[i] = person != null && today != null && person.getHolds().stream().anyMatch(h -> h.covers(today));
            if (!outside[i]) {
                total += m.share();
                anyInside = true;
                allRetired &= retired[i];
            }
        }
        allRetired &= anyInside;
        Long pot = sheetPot;
        Long parentId = above.get(g.id());
        Group parent = parentId != null ? groupsById.get(parentId) : null;
        if (parent != null && depth < 10) {
            long[] up = cutsOf(parent, groupsById, above, byId, sheetPot, today, known, depth + 1);
            int i = 0;
            while (i < parent.members().size() && !g.id().equals(parent.members().get(i).poolId())) {
                i++;
            }
            pot = up != null && i < up.length ? up[i] : null;
        }
        long[] result = null;
        if (total == WHOLE && pot != null && !allRetired) {
            boolean[] excluded = new boolean[n];
            for (int i = 0; i < n; i++) {
                excluded[i] = outside[i] || retired[i] || held[i];
            }
            result = cuts(pot, members, excluded);
        }
        known.put(g.id(), result);
        return result;
    }

    private static long[] cuts(long pot, List<Member> members, boolean[] excluded) {
        int n = members.size();
        long shares = 0;
        for (int i = 0; i < n; i++) {
            shares += excluded[i] ? 0 : members.get(i).share();
        }
        long[] out = new long[n];
        if (shares == 0) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            out[i] = excluded[i] ? 0 : cutOf(pot, members.get(i).share(), shares);
        }
        int smallest = -1;
        for (int i = 0; i < n; i++) {
            if (out[i] != 0 && (smallest < 0 || out[i] <= out[smallest])) {
                smallest = i;
            }
        }
        if (smallest < 0) {
            for (int i = 0; i < n; i++) {
                if (!excluded[i] && members.get(i).share() > 0) {
                    smallest = i;
                }
            }
        }
        if (smallest >= 0) {
            long sum = 0;
            for (long c : out) {
                sum += c;
            }
            out[smallest] += pot - sum;
        }
        return out;
    }

    /** The pot times the share divided by the shares, rounded half to even. */
    private static long cutOf(long pot, long share, long shares) {
        long product = pot * share;
        long whole = product / shares;
        long rest = (product % shares) * 2;
        boolean up = rest > shares || (rest == shares && whole % 2 == 1);
        return up ? whole + 1 : whole;
    }

    private static long cents(BigDecimal value) {
        return value.movePointRight(2).longValueExact();
    }
}
