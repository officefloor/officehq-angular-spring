package net.officefloor.hq.app.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's claim templates. A claim is added from one as the claim form adds one with the template's amount and
 * note at that moment and no date; a later change to the template leaves the claims made from it as they are.
 */
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

    /** The sheet's templates, by name. */
    @Transactional(readOnly = true)
    public List<ClaimTemplate> list(String sheetId) {
        return templates.findBySheetIdOrderByNameAsc(sheetId);
    }

    /**
     * Saves a template under its name, trimmed, replacing one already saved under it. Refused when the name is blank,
     * or the amount is one the claim form refuses without a claim to correct: negative, or with more than two
     * decimals.
     */
    @Transactional
    public ClaimTemplate save(String by, String sheetId, String name, BigDecimal amount, String note) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a template name.");
        }
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Enter an amount of at least 0 with up to two decimals.");
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        String text = note == null ? "" : note.strip();
        sheets.lock(sheetId);
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseGet(() -> new ClaimTemplate(sheetId, key, scaled, text));
        template.setAmount(scaled);
        template.setNote(text);
        ClaimTemplate saved = templates.saveAndFlush(template);
        audit.recordOnCommit("TEMPLATE_SAVED sheet=" + sheetId + " by=" + by + " name=" + key + " amount="
                + scaled.toPlainString() + " note=" + text);
        return saved;
    }

    /** Adds a draft claim for the person from the named template, by every rule of the claim form. */
    @Transactional
    public Claim addClaim(String by, String sheetId, Long personId, String name) {
        String key = name == null ? "" : name.strip();
        ClaimTemplate template = templates.findBySheetIdAndName(sheetId, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No template " + key + "."));
        return claims.add(by, sheetId, personId, template.getAmount(), null, template.getNote(), null, null, null);
    }
}
