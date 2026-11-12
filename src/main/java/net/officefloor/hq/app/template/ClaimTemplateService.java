package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's claim templates. */
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

    /** The sheet's claim templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** The sheet's template by the name; none by it is refused. */
    @Transactional(readOnly = true)
    public ClaimTemplate find(String sheetId, String name) {
        String n = name == null ? "" : name.trim();
        return templates.findBySheetIdAndName(sheetId, n)
                .orElseThrow(() -> new TemplateRefusedException("Choose a claim template."));
    }

    /** Save a template (name and note trimmed), replacing the one by the name; a blank name, or an amount a
     *  claim would refuse (a negative amount names no claim it corrects), is refused. */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        sheets.today(sheetId);
        String n = name == null ? "" : name.trim();
        String t = note == null ? "" : note.trim();
        if (n.isEmpty()) {
            throw new TemplateRefusedException("Enter a template name.");
        }
        if (amount.signum() < 0) {
            throw new TemplateRefusedException("Enter an amount of 0.00 or more.");
        }
        BigDecimal a = amount.setScale(2);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, n).orElse(null);
        if (template == null) {
            template = new ClaimTemplate(sheetId, n, a, t);
        } else {
            template.replace(a, t);
        }
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + n + " amount="
                + a.toPlainString() + " note=" + t);
        return saved;
    }
}
