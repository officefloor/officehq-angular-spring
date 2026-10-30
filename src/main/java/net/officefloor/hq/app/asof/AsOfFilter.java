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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * The as-of view of a sheet: a request on a sheet carrying an as-of date ({@code X-As-Of}, the date the
 * user's screen shows the sheet as of) reads the sheet as it stood at the end of that date (see
 * {@link SheetPasts}); every change it asks for is refused (409, writing nothing). Loading a sheet goes
 * through. A request without the date is the live sheet.
 */
@Component
public class AsOfFilter extends OncePerRequestFilter {

    static final String HEADER = "X-As-Of";

    private static final String PREFIX = "/api/sheets/";

    private static final String REFUSAL = "{\"message\":\"The sheet is shown as of a date: nothing on it can change.\"}";

    private final SheetPasts pasts;

    public AsOfFilter(SheetPasts pasts) {
        this.pasts = pasts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String asOf = request.getHeader(HEADER);
        return asOf == null || asOf.isBlank() || !path(request).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String sheet = UriUtils.decode(slash < 0 ? rest : rest.substring(0, slash), StandardCharsets.UTF_8).trim();
        LocalDate date;
        try {
            date = LocalDate.parse(request.getHeader(HEADER).trim());
        } catch (DateTimeParseException e) {
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }
        boolean reading = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        if (!reading) {
            if (path.endsWith("/load")) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(REFUSAL);
            return;
        }
        try {
            pasts.asOf(sheet, date, () -> {
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
