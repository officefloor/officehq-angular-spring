package net.officefloor.hq.app.claimtemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claim templates on a sheet: each a name, an amount and a note a claim can be added from. */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, SheetService sheets, Audit audit) {
        this.templates = templates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's claim templates, sorted by name. */
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** The sheet's template of this name (trimmed); none is not found. */
    public ClaimTemplate get(String sheetId, String name) {
        String key = name == null ? "" : name.trim();
        return templates.findBySheetIdAndName(sheetId, key)
                .orElseThrow(() -> new NoSuchElementException("No template " + key));
    }

    /** Save a template: its name and note trimmed, its amount one the claim form adds without a
     *  correction (0 or more, up to two decimals). Saving a name again replaces that template. A
     *  blank name or such an amount is refused. The sheet is held locked so the same name saved at
     *  the same moment is checked against itself. */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ClaimTemplateRefusedException("A template needs a name");
        }
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ClaimTemplateRefusedException("A template's amount is 0 or more with up to two decimals");
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        String text = note == null ? "" : note.trim();
        sheets.lock(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseGet(() -> new ClaimTemplate(sheetId, key, scaled, text));
        template.replace(scaled, text);
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + key
                + " amount=" + scaled.toPlainString() + " note=" + text);
        return saved;
    }
}
