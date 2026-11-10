package net.officefloor.hq.app.rollup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The figures of a sheet's descendants, which the screen adds to its own for the consolidated figures. */
@RestController
@RequestMapping("/api/rollup")
public class RollupController {

    public record DescendantsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record DescendantsResponse(int peopleCount, String dueTotal, String overdueTotal) {
    }

    private final RollupService service;

    public RollupController(RollupService service) {
        this.service = service;
    }

    // ends in /list: it only reads, so the as-of view answers it
    @PostMapping("/descendants/list")
    public DescendantsResponse descendants(@Valid @RequestBody DescendantsRequest req) {
        RollupService.Consolidated figures = service.descendants(req.sheet().trim());
        return new DescendantsResponse(figures.peopleCount(),
                figures.dueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                figures.overdueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
    }
}
