package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PoolService {

    /** A share percent as typed: up to two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public PoolService(PoolRepository pools, PersonRepository people, SheetService sheets, SheetRepository sheetRows,
            Audit audit) {
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's pools in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<Pool> list(String sheetId) {
        return pools.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a pool under its name (trimmed), refused when it is blank or the sheet has a pool of that name. */
    @Transactional
    public Pool add(String sheetId, String user, String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a pool name.");
        }
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (pools.existsBySheetIdAndName(sheetId, key)) throw alreadyPool(key);
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, key));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyPool(key);
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName());
        return saved;
    }

    /** Add a person of the sheet to a pool with a share percent of up to two decimals, more than 0.00
     *  and at most 100.00; refused when the person is already in the pool. */
    @Transactional
    public Pool addMember(String sheetId, String user, long poolId, long personId, String share) {
        String typed = share == null ? "" : share.trim();
        if (!SHARE.matcher(typed).matches() || new BigDecimal(typed).signum() <= 0
                || new BigDecimal(typed).compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Type a share of more than 0.00 and at most 100.00, with up to two decimals.");
        }
        BigDecimal value = new BigDecimal(typed).setScale(2);
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Pool pool = pools.findById(poolId).filter((p) -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a person of the sheet."));
        if (pool.hasMember(personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in the pool " + pool.getName() + ".");
        }
        pool.addMember(personId, value);
        pool = pools.saveAndFlush(pool);
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + value.toPlainString());
        return pool;
    }

    private static ResponseStatusException alreadyPool(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The sheet already has a pool \"" + name + "\".");
    }
}
