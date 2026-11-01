package net.officefloor.hq.app.asof;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Records a sheet in its {@link SheetHistory} after each change to it that went through (marked by
 * {@link AsOfGuard}), before the answer goes back: the user's next change cannot overtake it.
 */
@Component
public class SheetHistoryFilter extends OncePerRequestFilter {

    /** The request attribute naming the sheet a change that went through was made to. */
    static final String CHANGED = SheetHistoryFilter.class.getName() + ".changed";

    private final SheetHistory history;

    public SheetHistoryFilter(SheetHistory history) {
        this.history = history;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || !request.getRequestURI().startsWith("/api/sheets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper held = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, held);
            if (request.getAttribute(CHANGED) instanceof String sheetId) history.capture(sheetId);
        } finally {
            held.copyBodyToResponse();
        }
    }
}
