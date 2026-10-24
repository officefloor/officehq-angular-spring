package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claim templates on a sheet, one by each name. A claim added from a template is a claim added with
 * the template's amount and note as they are then and no date, by every rule of adding a claim (see
 * {@link ClaimService#add}); it keeps no link to the template, so changing the template later leaves it. */
@Service
public class ClaimTemplateService {

    private final SheetRepository sheets;
    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final Audit audit;

    public ClaimTemplateService(SheetRepository sheets, ClaimTemplateRepository templates, ClaimService claims,
            Audit audit) {
        this.sheets = sheets;
        this.templates = templates;
        this.claims = claims;
        this.audit = audit;
    }

    /** The sheet's claim templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> templates(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save the template {@code name} (trimmed, not blank) with {@code amount} and {@code note}, replacing
     * the one already saved by that name. */
    @Transactional
    public ClaimTemplate save(String sheetId, String by, String name, BigDecimal amount, String note) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (name.isEmpty()) throw new ClaimTemplateBlankException();
        String noted = note == null ? "" : note.trim();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name)
                .orElseGet(() -> new ClaimTemplate(sheetId, name));
        template.setAmount(amount);
        template.setNote(noted);
        template = templates.saveAndFlush(template);
        audit.change("TEMPLATE_SAVED", sheetId, by, "name=" + name, "amount=" + amount.toPlainString(),
                "note=" + noted);
        return template;
    }

    /** Add a DRAFT claim to the person with the template's amount and note, and no date. */
    @Transactional
    public Claim claim(String sheetId, String by, long personId, String name) {
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, name)
                .orElseThrow(ClaimTemplateNotFoundException::new);
        return claims.add(sheetId, by, personId, template.getAmount(), null, template.getNote(), null);
    }
}
