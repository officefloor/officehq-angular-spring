package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claim templates of a sheet, and the claims made from them. */
@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets,
            Audit audit) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The claim templates of the sheet in the order they were first saved. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Save a template under its (trimmed) name, replacing the one of that name; refused when the name
     *  is blank. */
    @Transactional
    public synchronized ClaimTemplate save(String sheetId, String user, String name, BigDecimal amount, String note) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a template name");
        }
        String text = note == null ? "" : note.trim();
        sheets.load(sheetId, user);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key).orElse(null);
        if (template == null) {
            template = new ClaimTemplate(sheetId, key, amount, text);
        } else {
            template.replace(amount, text);
        }
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + key
                + " amount=" + amount.setScale(2).toPlainString() + " note=" + text);
        return saved;
    }

    /** Add a draft claim for the person with the template's amount and note as they are now, dated as
     *  a claim with no date is, by every rule of the claim form; refused (404) when the sheet has no
     *  template of the name. */
    @Transactional
    public Claim addClaim(String sheetId, String user, long personId, String name) {
        String key = name == null ? "" : name.trim();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such template"));
        return claims.add(sheetId, user, personId, template.getAmount().setScale(2), null, template.getNote(),
                null, null, null);
    }
}
