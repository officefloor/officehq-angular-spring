package net.officefloor.hq.app.rate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
import org.springframework.stereotype.Service;

/**
 * The rates of a sheet's parent sheets, nearest first, up to {@link RateTable#ANCESTOR_LEVELS} levels up:
 * a grade with no rate in force on the sheet takes the first of them that has one. Reads the store directly,
 * within the caller's own transaction.
 */
@Service
public class ParentRates {

    private final SheetSettingRepository settings;
    private final RateRepository rates;

    public ParentRates(SheetSettingRepository settings, RateRepository rates) {
        this.settings = settings;
        this.rates = rates;
    }

    /** The rates of the sheet's parent, its parent's parent, and so on up to three levels. */
    public List<List<Rate>> of(String sheetId) {
        return from(sheetId, new DatedSettings(settings.findBySheetId(sheetId)).parent());
    }

    /** The rates of the parent given (null: none), then its own parents, for the sheet given. */
    public List<List<Rate>> from(String sheetId, String parent) {
        List<List<Rate>> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(List.of(sheetId));
        while (parent != null && found.size() < RateTable.ANCESTOR_LEVELS && seen.add(parent)) {
            found.add(rates.findBySheetId(parent));
            parent = new DatedSettings(settings.findBySheetId(parent)).parent();
        }
        return found;
    }
}
