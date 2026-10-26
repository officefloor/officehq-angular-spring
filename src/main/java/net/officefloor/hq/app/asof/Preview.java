package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The preview date a user's screen is set to: the screen sends it in a request header with every
 * read while it is set, and the read is answered with the sheet's current records as if the sheet's
 * today were that date. Changes are not affected: they use the sheet's real today. It belongs to that
 * screen only; the store keeps nothing of it.
 */
public final class Preview {

    /** The request header carrying the screen's preview date (yyyy-mm-dd). */
    public static final String HEADER = "X-Preview";

    private Preview() {
    }

    /** The preview date the request was sent with, if any. */
    public static Optional<LocalDate> of(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(header.trim()));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The preview date is not a date.");
        }
    }

    /** Whether a request is previewed when it carries a preview date: a GET (loading the sheet is not,
     *  so the screen keeps the sheet's real today for its defaults). */
    public static boolean previews(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }
}
