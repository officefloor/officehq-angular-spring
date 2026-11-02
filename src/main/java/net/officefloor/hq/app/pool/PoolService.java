package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pools of a sheet and their members. */
@Service
public class PoolService {

    /** A share as entered: a number with at most two decimals. */
    private static final Pattern SHARE = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    /** The total a pool's shares must come to for it to be balanced. */
    public static final BigDecimal WHOLE = new BigDecimal("100.00");

    /** A member as the pool shows them: the person, and their share. */
    public record Member(PoolMember member, Person person) {
    }

    /** A pool with its members in the order they were added, and the sheet's pot (null: none). */
    public record Listed(Pool pool, List<Member> members, BigDecimal pot) {

        /** The shares of the members added up, a retired member's (one with a retirement day set,
         *  whatever the day) counting as 0.00. */
        public BigDecimal totalShare() {
            return members.stream()
                    .filter(m -> !m.person().isRetired())
                    .map(m -> m.member().getShare())
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        }

        /** Whether the shares total exactly 100.00. */
        public boolean balanced() {
            return totalShare().compareTo(WHOLE) == 0;
        }

        /** Each member's cut of the pot, in the members' order: the pot times their share divided by
         *  100, rounded half to even to two decimals (a retired member's 0.00), with the remainder of
         *  the pot going to the member with the largest share (the earliest added on a tie), so the
         *  cuts total exactly the pot; none (an empty list) when there is no pot or the pool is not
         *  balanced. */
        public List<BigDecimal> cuts() {
            if (pot == null || !balanced()) {
                return List.of();
            }
            List<BigDecimal> shares = members.stream()
                    .map(m -> m.person().isRetired() ? BigDecimal.ZERO.setScale(2) : m.member().getShare())
                    .toList();
            List<BigDecimal> cuts = new ArrayList<>(shares.stream()
                    .map(sh -> pot.multiply(sh).divide(WHOLE, 2, RoundingMode.HALF_EVEN))
                    .toList());
            int largest = 0;
            for (int i = 1; i < shares.size(); i++) {
                if (shares.get(i).compareTo(shares.get(largest)) > 0) {
                    largest = i;
                }
            }
            BigDecimal remainder = pot.subtract(cuts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            cuts.set(largest, cuts.get(largest).add(remainder).setScale(2));
            return cuts;
        }
    }

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

    /** The pools of the sheet in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<Listed> list(String sheetId) {
        List<Pool> all = pools.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PoolMember>> byPool = members.findByPoolIdInOrderByIdAsc(all.stream().map(Pool::getId).toList())
                .stream().collect(Collectors.groupingBy(PoolMember::getPoolId));
        BigDecimal pot = sheets.pot(sheetId);
        return all.stream().map(p -> listed(p, byPool.getOrDefault(p.getId(), List.of()), byId, pot)).toList();
    }

    /** Add a pool with a (trimmed) name; refused when blank or already a pool of the sheet. */
    @Transactional
    public synchronized Listed add(String sheetId, String user, String name) {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a pool name");
        }
        sheets.load(sheetId, user);
        if (pools.existsBySheetIdAndName(sheetId, text)) {
            throw alreadyAPool();
        }
        Pool saved;
        try {
            saved = pools.saveAndFlush(new Pool(sheetId, text));
        } catch (DataIntegrityViolationException e) {
            throw alreadyAPool();
        }
        audit.recordOnCommit("POOL_ADDED sheet=" + sheetId + " by=" + user + " name=" + text);
        return new Listed(saved, List.of(), sheets.pot(sheetId));
    }

    /** Add a person of the sheet to a pool with a share (two decimals, above 0.00 and at most
     *  100.00); refused when the share is bad or the person is already in the pool. */
    @Transactional
    public synchronized Listed addMember(String sheetId, String user, long poolId, long personId, String share) {
        Pool pool = pools.findByIdAndSheetId(poolId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pool"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        BigDecimal amount = share(share);
        if (members.existsByPoolIdAndPersonId(poolId, personId)) {
            throw alreadyAMember();
        }
        try {
            members.saveAndFlush(new PoolMember(poolId, personId, amount));
        } catch (DataIntegrityViolationException e) {
            throw alreadyAMember();
        }
        audit.recordOnCommit("POOL_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " pool=" + pool.getName()
                + " name=" + person.getName() + " share=" + amount.toPlainString());
        return list(sheetId).stream().filter(l -> l.pool().getId().equals(poolId)).findFirst().orElseThrow();
    }

    /** A share as entered, to two decimals; refused unless above 0.00 and at most 100.00. */
    static BigDecimal share(String text) {
        String t = text == null ? "" : text.trim();
        if (!SHARE.matcher(t).matches()) {
            throw badShare();
        }
        BigDecimal amount = new BigDecimal(t).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(WHOLE) > 0) {
            throw badShare();
        }
        return amount;
    }

    private static Listed listed(Pool pool, List<PoolMember> of, Map<Long, Person> byId, BigDecimal pot) {
        return new Listed(pool, of.stream()
                .filter(m -> byId.containsKey(m.getPersonId()))
                .map(m -> new Member(m, byId.get(m.getPersonId())))
                .toList(), pot);
    }

    private static ResponseStatusException badShare() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A share is a percent with two decimals, more than 0.00 and at most 100.00");
    }

    private static ResponseStatusException alreadyAPool() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already a pool");
    }

    private static ResponseStatusException alreadyAMember() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the pool");
    }
}
