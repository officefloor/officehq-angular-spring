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
     *  their members, the crews as pools and the people as they stood. */
    public record PeriodPools(String period, LocalDate day, BigDecimal pot, List<PoolService.PoolWithMembers> pools,
            List<PoolService.CrewPool> crews, List<PersonThen> people) {
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
     *  while one is set), the earliest first, with the sheet's pools as they stood at the end of it. */
    public List<PeriodPools> byPeriod(String sheetId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        List<Map.Entry<String, LocalDate>> ended = new ArrayList<>();
        if (asOf == null) {
            for (Period period : periods.list(sheetId)) {
                ended.add(Map.entry(period.getName(), period.getTo()));
            }
        } else {
            for (History.PeriodThen period : periods.listAsOf(sheetId, asOf)) {
                ended.add(Map.entry(period.name(), period.to()));
            }
        }
        List<Person> everyone = people.findBySheetIdOrderByIdAsc(sheetId);
        List<PeriodPools> list = new ArrayList<>();
        for (Map.Entry<String, LocalDate> period : ended) {
            LocalDate day = period.getValue();
            if (day.isAfter(today)) {
                continue;
            }
            Map<Long, History.PersonThen> then = history.people(sheetId, day);
            Map<Long, List<PersonHold>> holds = personService.holds(sheetId, day);
            List<PersonThen> them = everyone.stream().map(p -> new PersonThen(p,
                    then.containsKey(p.getId()) ? then.get(p.getId()).retiredFrom() != null : p.isRetired(),
                    holds.getOrDefault(p.getId(), List.of()))).toList();
            list.add(new PeriodPools(period.getKey(), day, settings.pot(sheetId, day), pools.list(sheetId, day),
                    pools.crewPools(sheetId, day), them));
        }
        return list;
    }
}
