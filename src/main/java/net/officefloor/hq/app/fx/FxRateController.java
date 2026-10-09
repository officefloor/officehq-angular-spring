package net.officefloor.hq.app.fx;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The history of exchange rates into the home currency. */
@RestController
@RequestMapping("/api/fx-rates")
public class FxRateController {

    private final FxRateService service;

    public FxRateController(FxRateService service) {
        this.service = service;
    }

    @GetMapping
    public List<FxRateResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FxRateResponse record(@Valid @RequestBody FxRateRequest request) {
        return service.record(request);
    }
}
