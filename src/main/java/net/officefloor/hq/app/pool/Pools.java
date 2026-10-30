package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * The pools on a sheet, sorted by name, and their members: people of the sheet, each in a pool at most once, with a
 * share percent. A pool's total share counts a retired member's share (a retirement date set, whatever the date) as
 * 0.00; the pool is balanced when its total is exactly 100.00.
 */
@Service
public class Pools {

    /** The longest a pool's name may be. */
    static final int NAME_MAX = 100;

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member of a pool as shown: the person, their share and whether they are retired. */
    public record Member(long personId, String name, BigDecimal share, boolean retired) {
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
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        return all.stream().map(pool -> {
            List<Member> shown = byPool.getOrDefault(pool.getId(), List.of()).stream()
                    .filter(m -> byId.containsKey(m.getPersonId()))
                    .map(m -> {
                        Person p = byId.get(m.getPersonId());
                        return new Member(p.getId(), p.getName(), m.getShare().setScale(2), p.isRetired());
                    }).toList();
            BigDecimal total = shown.stream().filter(m -> !m.retired()).map(Member::share)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            return new Shown(pool.getName(), shown, total, total.compareTo(HUNDRED) == 0);
        }).toList();
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
        Pool pool = pools.findBySheetIdAndName(sheetId, poolName == null ? "" : poolName.strip())
                .orElseThrow(() -> new PoolRefused("No such pool on this sheet."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PoolRefused("Choose a person on this sheet."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new PoolRefused(person.getName() + " is in the pool already.");
        }
        members.saveAndFlush(new PoolMember(pool.getId(), person.getId(), share));
        events.publishEvent(new PoolMemberAdded(sheetId, user, pool.getName(), person.getName(), share));
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
