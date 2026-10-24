package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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

/** The pools on a sheet and their members. A pool's total share adds up its members' shares, a retired
 * member's (one with a retirement date set, whatever the date) counting as 0.00; the pool is balanced when
 * the total is exactly 100.00. Each member of a balanced pool cuts the sheet's pot times their share over 100,
 * rounded half to even to two decimals (a retired member 0.00), and the remainder of the pot over the cuts goes
 * to the member with the largest share, the earliest added on a tie, so the cuts add up to the pot exactly; an
 * unbalanced pool, or a sheet with no pot, has no cuts. */
@Service
public class PoolService {

    /** A share as typed: a percent with at most two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member of a pool as shown: the person, their name now, their share and their cut (null when none). */
    public record Member(long personId, String name, BigDecimal share, BigDecimal cut) {
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
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<SheetPoolMember>> byPool = new LinkedHashMap<>();
        for (SheetPoolMember m : members.findByPoolIdInOrderByIdAsc(all.stream().map(SheetPool::getId).toList())) {
            byPool.computeIfAbsent(m.getPoolId(), k -> new ArrayList<>()).add(m);
        }
        List<Pooled> found = new ArrayList<>();
        for (SheetPool pool : all) {
            List<SheetPoolMember> kept = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (SheetPoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                Person person = byId.get(m.getPersonId());
                if (person == null) continue;
                kept.add(m);
                if (!person.isRetired()) total = total.add(m.getShare());
            }
            total = total.setScale(2, RoundingMode.UNNECESSARY);
            boolean balanced = total.compareTo(HUNDRED) == 0;
            List<BigDecimal> cuts = balanced && pot != null ? cuts(pot, kept, byId) : null;
            List<Member> shown = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                SheetPoolMember m = kept.get(i);
                Person person = byId.get(m.getPersonId());
                shown.add(new Member(person.getId(), person.getName(), m.getShare().setScale(2),
                        cuts == null ? null : cuts.get(i)));
            }
            found.add(new Pooled(pool, shown, total, balanced));
        }
        return found;
    }

    /** The cuts of a balanced pool's members, in their order: the pot times each share over 100, half to even to
     * two decimals (a retired member's 0.00), the remainder going to the largest share, the earliest added on a tie. */
    private static List<BigDecimal> cuts(BigDecimal pot, List<SheetPoolMember> kept, Map<Long, Person> byId) {
        List<BigDecimal> cuts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int largest = -1;
        for (int i = 0; i < kept.size(); i++) {
            SheetPoolMember m = kept.get(i);
            if (byId.get(m.getPersonId()).isRetired()) {
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

    private static BigDecimal shareOf(String share) {
        String typed = share == null ? "" : share.trim();
        if (!SHARE.matcher(typed).matches()) throw new PoolShareException();
        BigDecimal amount = new BigDecimal(typed).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(HUNDRED) > 0) throw new PoolShareException();
        return amount;
    }
}
