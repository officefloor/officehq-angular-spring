package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's pools and their members. */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PersonRepository people, SheetService sheets, Audit audit) {
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a pool by a name (surrounding spaces removed); a blank name, or one already taken, is refused. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        sheets.today(sheetId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new PoolRefusedException("Enter a pool name.");
        }
        if (pools.existsBySheetIdAndName(sheetId, n)) {
            throw new PoolRefusedException("\"" + n + "\" is already a pool.");
        }
        Pool saved = pools.saveAndFlush(new Pool(sheetId, n));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + n);
        return saved;
    }

    /** Add a person of the sheet to a pool with a share percent; a person already in it, or a share not more
     *  than 0.00 and at most 100.00 in two decimals, is refused. */
    @Transactional
    public Pool addMember(String sheetId, String user, long poolId, Long personId, BigDecimal share) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new PoolRefusedException("No such pool."));
        Person person = personId == null ? null : people.findByIdAndSheetId(personId, sheetId).orElse(null);
        if (person == null) {
            throw new PoolRefusedException("Choose a person.");
        }
        if (share == null || share.stripTrailingZeros().scale() > 2 || share.signum() <= 0
                || share.compareTo(HUNDRED) > 0) {
            throw new PoolRefusedException("Enter a share more than 0.00 and at most 100.00, in two decimals.");
        }
        if (pool.hasMember(person.getId())) {
            throw new PoolRefusedException(person.getName() + " is already in " + pool.getName() + ".");
        }
        BigDecimal s = share.setScale(2);
        pool.addMember(person.getId(), s);
        Pool saved = pools.saveAndFlush(pool);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + s.toPlainString());
        return saved;
    }
}
