package net.officefloor.hq.app.history;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** How a row's state is kept in the sheet's history: its entity's fields as JSON, read back into a
 * detached entity of the same class. */
final class HistoryJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultVisibility(v -> v.withFieldVisibility(Visibility.ANY)
                    .withGetterVisibility(Visibility.NONE)
                    .withIsGetterVisibility(Visibility.NONE)
                    .withSetterVisibility(Visibility.NONE))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private HistoryJson() {
    }
}
