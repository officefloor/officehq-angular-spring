package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** The sheet's pools as they stood at the end of each period that has ended, so each member's cut can
 *  be shown as it stood then. */
@Service
public class PoolHistoryService {

    /** A person as a pool saw them at the end of a day: whether a retirement date was set, and the holds
     *  put on them by then. */
    public record PersonThen(Person person, boolean retired, List<PersonHold> holds) {
    }

    /** A period that has ended and, at the end of its to-date, the pot (null for none), the pools with
     *  their members, the crews as pools and the people as they stood; or, for one closed or
     *  soft-closed, those fixed when it was (frozen, JSON, the rest unused). */
    public record PeriodPools(String period, LocalDate day, BigDecimal pot, List<PoolService.PoolWithMembers> pools,
            List<PoolService.CrewPool> crews, List<PersonThen> people, String frozen) {
    }

    private final PoolService pools;
    private final PeriodService periods;
    private final PersonService personService;
    private final PersonRepository people;
    private final SettingService settings;
    private final SheetService sheets;
    private final History history;

    public PoolHistoryService(PoolService pools, PeriodService periods, PersonService personService,
            PersonRepository people, SettingService settings, SheetService sheets, History history) {
        this.pools = pools;
        this.periods = periods;
        this.personService = personService;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
        this.history = history;
    }

    /** Each period whose to-date is on or before the sheet's today (the as-of date standing in for it
     *  while one is set), the earliest first, with the sheet's pools as they stood at the end of it; a
     *  closed or soft-closed one (while no as-of date is set) with those fixed when it was closed. */
    public List<PeriodPools> byPeriod(String sheetId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        List<PeriodPools> list = new ArrayList<>();
        List<Person> everyone = people.findBySheetIdOrderByIdAsc(sheetId);
        if (asOf == null) {
            for (Period period : periods.list(sheetId)) {
                if (period.getTo().isAfter(today)) {
                    continue;
                }
                list.add(period.getFrozenPools() != null
                        ? new PeriodPools(period.getName(), period.getTo(), null, List.of(), List.of(), List.of(),
                                period.getFrozenPools())
                        : at(sheetId, period.getName(), period.getTo(), everyone));
            }
        } else {
            for (History.PeriodThen period : periods.listAsOf(sheetId, asOf)) {
                if (!period.to().isAfter(today)) {
                    list.add(at(sheetId, period.name(), period.to(), everyone));
                }
            }
        }
        return list;
    }

    /** The period's pools, pot and people as they stand now, at the sheet's today, to be fixed on it. */
    public PeriodPools now(String sheetId, String period) {
        return at(sheetId, period, sheets.today(sheetId), people.findBySheetIdOrderByIdAsc(sheetId));
    }

    /** The sheet's pools, pot and people as they stood at the end of a day. */
    private PeriodPools at(String sheetId, String period, LocalDate day, List<Person> everyone) {
        Map<Long, History.PersonThen> then = history.people(sheetId, day);
        Map<Long, List<PersonHold>> holds = personService.holds(sheetId, day);
        List<PersonThen> them = everyone.stream().map(p -> new PersonThen(p,
                then.containsKey(p.getId()) ? then.get(p.getId()).retiredFrom() != null : p.isRetired(),
                holds.getOrDefault(p.getId(), List.of()))).toList();
        return new PeriodPools(period, day, settings.pot(sheetId, day), pools.list(sheetId, day),
                pools.crewPools(sheetId, day), them, null);
    }
}
