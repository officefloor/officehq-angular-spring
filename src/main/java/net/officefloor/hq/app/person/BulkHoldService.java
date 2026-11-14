package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Holds a selection of people together. */
@Service
public class BulkHoldService {

    private final PersonService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkHoldService(PersonService service, RollupService rollups,
            SettingService settings, SheetService sheets) {
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Hold each selected person, in list order, from a date (null takes the sheet's today) to an
     *  optional to-date exactly as the row's hold would, each followed by the figures it moved. Refused
     *  as a whole, writing nothing, when any selected person is not on the sheet or the to-date is before
     *  the from-date. A viewer, or anyone on an archived sheet, holds no one. */
    @Transactional
    public List<Person> hold(String sheetId, String user, Set<Long> ids, LocalDate from, LocalDate to) {
        List<Person> selected = service.list(sheetId).stream()
                .filter(person -> ids.contains(person.getId())).toList();
        if (selected.size() != ids.size()) {
            throw new NoSuchElementException("Not every selected person is on sheet " + sheetId);
        }
        List<Person> held = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: no selected person is held.
            return held;
        }
        for (Person person : selected) {
            held.add(rollups.track(sheetId, user, () -> service.hold(sheetId, user, person.getId(), from, to)));
        }
        return held;
    }
}
