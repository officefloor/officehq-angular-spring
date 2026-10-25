package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import net.officefloor.hq.app.ViewerGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The as-of date a user's screen is set to: the screen sends it in a request header with every
 * request while it is set. It belongs to that screen only; the store keeps nothing of it.
 */
public final class AsOf {

    /** The request header carrying the screen's as-of date (yyyy-mm-dd). */
    public static final String HEADER = "X-As-Of";

    private AsOf() {
    }

    /** The as-of date the request was sent with, if any. */
    public static Optional<LocalDate> of(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(header.trim()));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The as-of date is not a date.");
        }
    }

    /** The id of the sheet a request is on, if it is on one. */
    public static Optional<String> sheetOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(ViewerGuard.SHEETS)) return Optional.empty();
        String rest = path.substring(ViewerGuard.SHEETS.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return Optional.empty();
        String id = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8).trim();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    /** Whether a request only reads its sheet: a GET, or loading the sheet. */
    public static boolean reads(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || ("POST".equals(request.getMethod()) && path.endsWith("/load"));
    }
}
