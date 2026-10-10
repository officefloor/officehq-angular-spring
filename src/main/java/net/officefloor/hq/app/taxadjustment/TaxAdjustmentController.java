package net.officefloor.hq.app.taxadjustment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Manual adjustments to the tax owed for a period. */
@RestController
@RequestMapping("/api/tax-adjustments")
public class TaxAdjustmentController {

    private final TaxAdjustmentService service;

    public TaxAdjustmentController(TaxAdjustmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaxAdjustmentResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaxAdjustmentResponse record(@Valid @RequestBody TaxAdjustmentRequest request) {
        return service.record(request);
    }
}
