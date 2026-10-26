package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's pools and their members. A person is in a pool at most once, with a share percent of more than 0.00 and
 * at most 100.00, and may be in several pools.
 */
@Service
public class PoolService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final PoolRepository pools;
    private final PoolMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PoolService(PoolRepository pools, PoolMemberRepository members, PersonRepository people,
            SheetService sheets, Audit audit) {
        this.pools = pools;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's pools, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The members of every pool on the sheet, in the order they were added. */
    @Transactional(readOnly = true)
    public List<PoolMember> members(String sheetId) {
        return members.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Adds a pool under its name, trimmed; refused when blank or already a pool's name. */
    @Transactional
    public Pool add(String by, String sheetId, String name) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a pool name.");
        }
        sheets.lock(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, key + " is already a pool.");
        }
        Pool added = pools.saveAndFlush(new Pool(sheetId, key));
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + by + " name=" + key);
        return added;
    }

    /**
     * Adds the person to the pool with a share; refused when the person is already in it, or the share is not more
     * than 0.00 and at most 100.00 with up to two decimals.
     */
    @Transactional
    public PoolMember addMember(String by, String sheetId, Long poolId, Long personId, BigDecimal share) {
        if (share == null || share.signum() <= 0 || share.compareTo(HUNDRED) > 0
                || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Enter a share of more than 0.00 and at most 100.00, with up to two decimals.");
        }
        BigDecimal scaled = share.setScale(2, RoundingMode.UNNECESSARY);
        sheets.lock(sheetId);
        Pool pool = pools.findById(poolId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Choose a person."));
        if (members.existsByPoolIdAndPersonId(pool.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in " + pool.getName() + ".");
        }
        PoolMember added = members.saveAndFlush(new PoolMember(sheetId, pool.getId(), person.getId(), scaled));
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + by + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + scaled.toPlainString());
        return added;
    }
}
