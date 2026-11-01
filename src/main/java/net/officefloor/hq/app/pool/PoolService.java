package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

/**
 * A sheet's pools, each name once, and their members: people of the sheet, each at most once in a
 * pool, with a share percent. A person can be in several pools. A pool is balanced when its shares
 * total exactly 100.00; a retired member's share (retirement date set, whatever the date) counts as
 * 0.00 toward the total. Each member of a balanced pool cuts the sheet's pot (none: no cuts): the pot
 * times their share over 100, rounded half to even to two decimals, a retired member 0.00; the
 * remainder, the pot less the cuts, goes to the member with the largest share (a retired one's counting
 * 0.00), the earliest added on a tie, so the cuts total exactly the pot.
 */
@Service
public class PoolService {

    static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member as shown: the person, their share, whether they are retired, and their cut (null: none). */
    public record Member(Long personId, String name, BigDecimal share, boolean retired, BigDecimal cut) {}

    /** A pool as shown: its members in the order added, its total share, and whether it is balanced. */
    public record PoolLine(Long id, String name, List<Member> members, BigDecimal totalShare, boolean balanced) {}

    private final SheetRepository sheets;
    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final Audit audit;

    public PoolService(SheetRepository sheets, PoolRepository pools, PoolMemberRepository members,
            PersonRepository people, Audit audit) {
        this.sheets = sheets;
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<PoolLine> pools(String sheetId) {
        BigDecimal pot = sheets.findById(sheetId).map(Sheet::getPot).orElse(null);
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PoolMember::getPoolId));
        List<PoolLine> lines = new ArrayList<>();
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Member> shown = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (PoolMember m : byPool.getOrDefault(pool.getId(), List.of())) {
                Person p = byId.get(m.getPersonId());
                if (p == null) continue;
                shown.add(new Member(p.getId(), p.getName(), m.getShare().setScale(2), p.isRetired(), null));
                if (!p.isRetired()) total = total.add(m.getShare());
            }
            boolean balanced = total.compareTo(HUNDRED) == 0;
            lines.add(new PoolLine(pool.getId(), pool.getName(), balanced && pot != null ? cut(shown, pot) : shown,
                    total, balanced));
        }
        return lines;
    }

    /**
     * The members with their cuts of the pot: each the pot times their share over 100, rounded half to
     * even, a retired member 0.00; the remainder goes to the largest share, the earliest added on a tie.
     */
    private static List<Member> cut(List<Member> shown, BigDecimal pot) {
        BigDecimal[] cuts = new BigDecimal[shown.size()];
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int largest = -1;
        BigDecimal largestShare = null;
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            BigDecimal share = m.retired() ? BigDecimal.ZERO.setScale(2) : m.share();
            cuts[i] = pot.multiply(share).divide(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
            sum = sum.add(cuts[i]);
            if (largestShare == null || share.compareTo(largestShare) > 0) {
                largest = i;
                largestShare = share;
            }
        }
        if (largest >= 0) cuts[largest] = cuts[largest].add(pot.subtract(sum));
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Member m = shown.get(i);
            out.add(new Member(m.personId(), m.name(), m.share(), m.retired(), cuts[i]));
        }
        return out;
    }

    /** Add a pool, its name trimmed; refused when blank or already a pool of the sheet. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String text = name == null ? "" : name.strip();
        if (text.isEmpty()) throw new PoolNameBlankException();
        if (pools.existsBySheetIdAndName(sheetId, text)) throw new DuplicatePoolNameException();
        Pool saved = pools.saveAndFlush(new Pool(sheetId, text));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user.strip() + " name=" + text);
        return saved;
    }

    /**
     * Add a person of the sheet to a pool with a share (two decimals, more than 0.00 and at most
     * 100.00); refused when they are already in it or the share is bad.
     */
    @Transactional
    public void addMember(String sheetId, String user, Long poolId, Long personId, String share) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId).orElseThrow(PoolNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        BigDecimal value = shareOf(share);
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) throw new DuplicatePoolMemberException();
        members.saveAndFlush(new PoolMember(pool.getId(), sheetId, person.getId(), value));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user.strip() + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + value.toPlainString());
    }

    /** The share as two decimals; refused unless at most two decimals, more than 0.00 and at most 100.00. */
    private static BigDecimal shareOf(String share) {
        String text = share == null ? "" : share.strip();
        if (!text.matches("\\d{1,3}(\\.\\d{1,2})?")) throw new PoolShareException();
        BigDecimal value = new BigDecimal(text).setScale(2);
        if (value.signum() <= 0 || value.compareTo(HUNDRED) > 0) throw new PoolShareException();
        return value;
    }
}
