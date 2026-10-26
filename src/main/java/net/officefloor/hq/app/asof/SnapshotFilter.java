package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Once a request that may change a sheet has gone through, stores the state the sheet now stands in (see
 * {@link AsOfService#capture}). The answer is held back until it is stored, so the next change, which the screen makes
 * only once it has the answer, never comes first.
 */
@Component
public class SnapshotFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotFilter.class);

    /** A request on a sheet: /api/sheets/{id}, and anything under it. */
    private static final Pattern SHEET = Pattern.compile("^/api/sheets/([A-Za-z0-9_.-]+)(?:/.*)?$");

    private final AsOfService asOf;

    public SnapshotFilter(AsOfService asOf) {
        this.asOf = asOf;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)
                || sheetId(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper held = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, held);
            String header = request.getHeader(AsOfGuard.HEADER);
            // a request answered as of a date changes nothing
            if (held.getStatus() < 400 && (header == null || header.isBlank())) {
                try {
                    asOf.capture(sheetId(request));
                } catch (RuntimeException e) {
                    LOG.error("Could not store the state of sheet {}", sheetId(request), e);
                }
            }
        } finally {
            held.copyBodyToResponse();
        }
    }

    private static String sheetId(HttpServletRequest request) {
        Matcher m = SHEET.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        return m.matches() ? m.group(1) : null;
    }
}
