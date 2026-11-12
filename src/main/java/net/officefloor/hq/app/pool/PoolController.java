package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's pools and their members. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** A member: a retired one (a retirement date set, whatever the date) counts 0.00 toward the total. Its
     *  cut of the sheet's pot (two decimals); none when the pool is unbalanced or there is no pot. */
    public record PoolMemberView(long personId, String name, String share, boolean retired, String cut) {
    }

    /** A pool, with its total share and whether that is exactly 100.00. */
    public record PoolView(long id, String name, List<PoolMemberView> members, String totalShare, boolean balanced) {

        static PoolView of(Pool pool, Map<Long, Person> people, BigDecimal pot) {
            List<PoolMember> all = pool.getMembers();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            boolean[] retired = new boolean[all.size()];
            for (int i = 0; i < all.size(); i++) {
                Person p = people.get(all.get(i).getPersonId());
                retired[i] = p != null && p.isRetired();
                if (!retired[i]) {
                    total = total.add(all.get(i).getShare());
                }
            }
            boolean balanced = total.compareTo(HUNDRED) == 0;
            BigDecimal[] cuts = balanced && pot != null ? cuts(all, retired, pot) : new BigDecimal[all.size()];
            List<PoolMemberView> members = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                PoolMember m = all.get(i);
                Person p = people.get(m.getPersonId());
                members.add(new PoolMemberView(m.getPersonId(), p == null ? "" : p.getName(),
                        m.getShare().setScale(2).toPlainString(), retired[i],
                        cuts[i] == null ? null : cuts[i].toPlainString()));
            }
            return new PoolView(pool.getId(), pool.getName(), members, total.setScale(2).toPlainString(), balanced);
        }

        /** Each member's cut of the pot: the pot times their share divided by 100, rounded half to even to two
         *  decimals (0.00 for a retired one); the remainder, the pot minus the cuts added up, goes to the member
         *  with the largest share, the earliest added on a tie, so the cuts total exactly the pot. */
        private static BigDecimal[] cuts(List<PoolMember> members, boolean[] retired, BigDecimal pot) {
            BigDecimal[] cuts = new BigDecimal[members.size()];
            BigDecimal sum = BigDecimal.ZERO.setScale(2);
            int largest = -1;
            for (int i = 0; i < members.size(); i++) {
                BigDecimal share = members.get(i).getShare();
                cuts[i] = retired[i] ? BigDecimal.ZERO.setScale(2)
                        : pot.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
                sum = sum.add(cuts[i]);
                if (!retired[i] && (largest < 0 || share.compareTo(members.get(largest).getShare()) > 0)) {
                    largest = i;
                }
            }
            if (largest >= 0) {
                cuts[largest] = cuts[largest].add(pot.setScale(2).subtract(sum));
            }
            return cuts;
        }
    }

    public record PoolRequest(@Size(max = 200) String name) {
    }

    public record PoolMemberRequest(Long personId, BigDecimal share) {
    }

    private final PoolService service;
    private final PersonRepository people;
    private final SheetService sheets;

    public PoolController(PoolService service, PersonRepository people, SheetService sheets) {
        this.service = service;
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's pools, in the order they were added. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        String sid = sheetId.trim();
        Map<Long, Person> byId = people(sid);
        BigDecimal pot = sheets.pot(sid);
        return service.list(sid).stream().map(p -> PoolView.of(p, byId, pot)).toList();
    }

    /** Add a pool to the sheet. */
    @PostMapping
    public PoolView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolRequest request) {
        String sid = sheetId.trim();
        return PoolView.of(service.add(sid, user.trim(), request.name()), people(sid), sheets.pot(sid));
    }

    /** Add a person to a pool with a share. */
    @PostMapping("/{poolId}/members")
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolMemberRequest request) {
        String sid = sheetId.trim();
        Pool pool = service.addMember(sid, user.trim(), poolId, request.personId(), request.share());
        return PoolView.of(pool, people(sid), sheets.pot(sid));
    }

    private Map<Long, Person> people(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
    }

    /** A pool or member that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(PoolRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(PoolRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
