package net.officefloor.hq.app.setting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.sheet.DatedSettingLine;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sheet's policy versions, oldest first, each with the setting it recorded and how many claims it is the policy version of. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class PolicyVersionController {

    /** A policy version: its number, from-date (null: none, the start), key, value (null: none) and claim count. */
    public record PolicyVersionView(int version, LocalDate from, String key, String value, long claims) {}

    private final SheetRepository sheets;
    private final ClaimRepository claims;

    public PolicyVersionController(SheetRepository sheets, ClaimRepository claims) {
        this.sheets = sheets;
        this.claims = claims;
    }

    @GetMapping("/policy-versions")
    @Transactional(readOnly = true)
    public List<PolicyVersionView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        if (sheet == null) return List.of();
        List<DatedSettingLine> log = sheet.getPolicyLog();
        long[] counts = new long[log.size() + 1];
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) counts[sheet.policyVersionOn(claim.getDate())]++;
        List<PolicyVersionView> views = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            DatedSettingLine line = log.get(i);
            views.add(new PolicyVersionView(i + 1, line.getFrom(), line.getKey(), line.getValue(), counts[i + 1]));
        }
        return views;
    }
}
