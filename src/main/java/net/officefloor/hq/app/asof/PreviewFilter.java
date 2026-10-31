package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * The preview of a sheet: a read on a sheet carrying a preview date ({@code X-Preview}, the date the user's
 * screen previews the sheet's today as) reads the sheet's current records as if its today were that date (see
 * {@link SheetPasts#preview}). A change goes through as it would without it, on the sheet's real today. A
 * request shown as of a date ({@code X-As-Of}) is not previewed.
 */
@Component
public class PreviewFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Preview";

    private static final String PREFIX = "/api/sheets/";

    private final SheetPasts pasts;

    public PreviewFilter(SheetPasts pasts) {
        this.pasts = pasts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String preview = request.getHeader(HEADER);
        String asOf = request.getHeader(AsOfFilter.HEADER);
        boolean reading = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        return preview == null || preview.isBlank() || (asOf != null && !asOf.isBlank()) || !reading
                || !path(request).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rest = path(request).substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String sheet = UriUtils.decode(slash < 0 ? rest : rest.substring(0, slash), StandardCharsets.UTF_8).trim();
        LocalDate date;
        try {
            date = LocalDate.parse(request.getHeader(HEADER).trim());
        } catch (DateTimeParseException e) {
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }
        try {
            pasts.preview(sheet, date, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
