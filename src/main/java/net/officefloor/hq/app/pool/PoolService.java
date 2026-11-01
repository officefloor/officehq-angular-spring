package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's pools, each name once, and their members: people of the sheet, each at most once in a
 * pool, with a share percent. A person can be in several pools. A pool is balanced when its shares
 * total exactly 100.00; a retired member's share (retirement date set, whatever the date) counts as
 * 0.00 toward the total.
 */
@Service
public class PoolService {

    static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member as shown: the person, their share, and whether they are retired. */
    public record Member(Long personId, String name, BigDecimal share, boolean retired) {}

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
                shown.add(new Member(p.getId(), p.getName(), m.getShare().setScale(2), p.isRetired()));
                if (!p.isRetired()) total = total.add(m.getShare());
            }
            lines.add(new PoolLine(pool.getId(), pool.getName(), shown, total, total.compareTo(HUNDRED) == 0));
        }
        return lines;
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
