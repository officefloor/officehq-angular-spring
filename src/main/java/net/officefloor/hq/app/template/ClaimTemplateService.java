package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimTemplateService {

    /** An amount as the claim form takes it: up to two decimals. */
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,10}(\\.\\d{1,2})?");

    private final ClaimTemplateRepository templates;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public ClaimTemplateService(ClaimTemplateRepository templates, ClaimService claims, SheetService sheets,
            SheetRepository sheetRows, Audit audit) {
        this.templates = templates;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's claim templates by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /** Save a template under its name (trimmed), replacing the one of that name. Refused for a blank
     *  name and for an amount the claim form would refuse: not a number of up to two decimals, or
     *  negative (a correction, which a template cannot name the claim it corrects for). */
    @Transactional
    public ClaimTemplate save(String sheetId, String user, String name, String amount, String note) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a template name.");
        }
        String typed = amount == null ? "" : amount.trim();
        if (!AMOUNT.matcher(typed).matches() || new BigDecimal(typed).signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Type an amount of zero or more with up to two decimals.");
        }
        BigDecimal value = new BigDecimal(typed);
        String text = note == null ? "" : note.trim();
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key).orElse(null);
        if (template == null) template = new ClaimTemplate(sheetId, key, value, text);
        else template.set(value, text);
        template = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + user + " name=" + template.getName()
                + " amount=" + template.getAmount().toPlainString() + " note=" + template.getNote());
        return template;
    }

    /** Add a claim to a person with the template's amount and note as they are now, and no date, by
     *  every rule of the claim form. */
    @Transactional
    public Claim addClaim(String sheetId, String user, long personId, String name) {
        String key = name == null ? "" : name.trim();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such template."));
        return claims.add(sheetId, user, personId, template.getAmount(), null, template.getNote(), null, null, null);
    }
}
