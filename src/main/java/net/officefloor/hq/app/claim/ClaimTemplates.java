package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claim templates on a sheet, sorted by name; saving a name again replaces that template. A claim added from a
 * template is a DRAFT taking the template's amount and note at that moment, dated as a claim with no date, by every
 * rule of the claim form; changing the template later leaves the claims made from it as they are.
 */
@Service
public class ClaimTemplates {

    /** The longest a template's name may be. */
    static final int NAME_MAX = 100;

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public ClaimTemplates(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets,
            SheetRepository sheetRows, ApplicationEventPublisher events) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The templates on the sheet, sorted by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /**
     * Save a template under its name, trimmed, replacing the one of that name; refused when the name is blank or
     * too long. The amount is one the claim form takes for a claim that corrects nothing.
     */
    @Transactional
    public void save(String sheetId, String user, String text, BigDecimal amount, String note) {
        String name = text == null ? "" : text.strip();
        if (name.isEmpty()) {
            throw new ClaimTemplateRefused("Enter a name for the template.");
        }
        if (name.length() > NAME_MAX) {
            throw new ClaimTemplateRefused("The template's name is too long.");
        }
        String kept = note == null ? "" : note;
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name).orElse(null);
        if (template == null) {
            templates.saveAndFlush(new ClaimTemplate(sheetId, name, amount, kept));
        } else {
            template.setAmount(amount);
            template.setNote(kept);
            templates.flush();
        }
        events.publishEvent(new TemplateSaved(sheetId, user, name, amount, kept));
    }

    /** Add a DRAFT claim for the person with the named template's amount and note now. Returns the person. */
    @Transactional
    public long addClaim(String sheetId, String user, long personId, String name) {
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name == null ? "" : name.strip())
                .orElseThrow(() -> new ClaimTemplateRefused("No such template on this sheet."));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote());
    }
}
