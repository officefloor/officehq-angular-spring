package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.asof.AsOfRead;
import net.officefloor.hq.app.setting.OpenToViewer;
import net.officefloor.hq.app.setting.SettingController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /**
     * A sheet as the screen shows it; the floor, the terms, the late percent and the cap are those in
     * force on its today: the floor has two decimals, the terms are in days, the late percent has two
     * decimals; the policy date is null when blank (the sheet's today); the cap has two decimals, null
     * when none; dated has every value recorded for its dated settings, by key; archived is whether a
     * steward has archived it; last batch is the number of its last bulk settle batch, null when none; the review SLA is in days, null when none.
     */
    public record SheetView(String id, LocalDate today, String floor, int terms, String latePct,
            LocalDate policyDate, String cap, Map<String, List<SettingController.DatedView>> dated,
            boolean archived, Integer lastBatch, Integer reviewSla) {
        static SheetView of(Sheet sheet) {
            LocalDate today = sheet.getToday();
            return new SheetView(sheet.getId(), today, sheet.floorOn(today).toPlainString(),
                    sheet.termsOn(today), sheet.latePctOn(today).toPlainString(), sheet.getPolicyDate(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString(),
                    SettingController.datedViews(sheet.getDated()), sheet.isArchived(),
                    sheet.getLastSettleBatch() == 0 ? null : sheet.getLastSettleBatch(), sheet.getReviewSla());
        }
    }

    public record TodayRequest(@NotNull LocalDate today) {}

    /** The id of the sheet to clone this one to: one never used. */
    public record CloneRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String to) {}

    /** A clone made: the id of the sheet it was made to. */
    public record CloneView(String to) {}

    private final SheetService service;
    private final SheetCloneService clones;

    public SheetController(SheetService service, SheetCloneService clones) {
        this.service = service;
        this.clones = clones;
    }

    /** Load (or start) a sheet by id for the signed-in user. */
    @OpenToViewer
    @AsOfRead
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SheetView.of(service.load(id, user.trim()));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TodayRequest request) {
        return SheetView.of(service.setToday(id, user, request.today()));
    }

    /** Archive the sheet: a steward only; an archived sheet refuses it as it does every change. */
    @PostMapping("/{id}/archive")
    public SheetView archive(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SheetView.of(service.archive(id, user.trim()));
    }

    /** Clone the sheet to another id: a steward only, and only to a sheet never used. */
    @PostMapping("/{id}/clone")
    public CloneView cloneTo(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CloneRequest request) {
        clones.cloneTo(id, user.trim(), request.to().trim());
        return new CloneView(request.to().trim());
    }
}
