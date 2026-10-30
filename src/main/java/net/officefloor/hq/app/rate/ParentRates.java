package net.officefloor.hq.app.rate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
import org.springframework.stereotype.Service;

/**
 * The rates of a sheet's parent sheets, nearest first, up to {@link RateTable#ANCESTOR_LEVELS} levels up; then,
 * for a sheet that was cloned, the rates of the sheet it was cloned from, its parents (up to three levels) and
 * its own source, and so on: a grade with no rate in force on the sheet takes the first of them that has one.
 * Each sheet is looked in once. Reads the store directly, within the caller's own transaction.
 */
@Service
public class ParentRates {

    private final SheetSettingRepository settings;
    private final SheetRepository sheets;
    private final RateRepository rates;

    public ParentRates(SheetSettingRepository settings, SheetRepository sheets, RateRepository rates) {
        this.settings = settings;
        this.sheets = sheets;
        this.rates = rates;
    }

    /** The rates of the sheet's parent, its parent's parent, and so on up to three levels, then its source's. */
    public List<List<Rate>> of(String sheetId) {
        return from(sheetId, parentOf(sheetId));
    }

    /** The rates of the parent given (null: none), then its own parents, then the source sheets', for the sheet given. */
    public List<List<Rate>> from(String sheetId, String parent) {
        List<List<Rate>> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(List.of(sheetId));
        climb(parent, found, seen);
        String source = sourceOf(sheetId);
        while (source != null && seen.add(source)) {
            found.add(rates.findBySheetId(source));
            climb(parentOf(source), found, seen);
            source = sourceOf(source);
        }
        return found;
    }

    /** Add the rates of the parent given and its own parents, up to three levels. */
    private void climb(String parent, List<List<Rate>> found, Set<String> seen) {
        for (int level = 0; parent != null && level < RateTable.ANCESTOR_LEVELS && seen.add(parent); level++) {
            found.add(rates.findBySheetId(parent));
            parent = parentOf(parent);
        }
    }

    private String parentOf(String sheetId) {
        return new DatedSettings(settings.findBySheetId(sheetId)).parent();
    }

    private String sourceOf(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getClonedFrom).orElse(null);
    }
}
