package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The as-of view, which belongs to the user's screen: a request to a sheet carrying an
 * {@value #HEADER} date is shown as the sheet stood at the end of that date, and a change asked
 * with one is refused before anything is written. Every other change made to a sheet that has gone
 * through is marked for {@link SheetHistoryFilter} to record in its {@link SheetHistory}.
 *
 * <p>A read as of a date runs in a transaction that puts the sheet back as it stood then, with that
 * date as its today, and is rolled back once the read is answered, so it changes nothing and audits
 * nothing.
 */
@Configuration
public class AsOfGuard implements WebMvcConfigurer, HandlerInterceptor {

    /** The request header carrying the user's as-of date (yyyy-mm-dd). */
    public static final String HEADER = "X-As-Of";

    private static final String VIEW = AsOfGuard.class.getName() + ".view";

    /** A read as of a date under way: its transaction and the lock it holds on its sheet. */
    private record View(TransactionStatus transaction, ReentrantLock lock) {}

    private final SheetHistory history;
    private final PlatformTransactionManager transactions;
    /** One as-of read of a sheet at a time: each puts back the same rows. */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AsOfGuard(SheetHistory history, PlatformTransactionManager transactions) {
        this.history = history;
        this.transactions = transactions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        String sheetId = sheetId(request);
        String header = request.getHeader(HEADER);
        if (sheetId == null || header == null || header.isBlank()) return true;
        if (!isRead(request) && !method.hasMethodAnnotation(AsOfRead.class)) throw new AsOfRefusedException();
        LocalDate asOf;
        try {
            asOf = LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            throw new AsOfRefusedException();
        }
        // a sheet never started is started as usual
        if (!history.exists(sheetId)) return true;
        ReentrantLock lock = locks.computeIfAbsent(sheetId, id -> new ReentrantLock());
        lock.lock();
        TransactionStatus transaction = null;
        try {
            transaction = transactions.getTransaction(new DefaultTransactionDefinition());
            history.restore(sheetId, asOf);
        } catch (RuntimeException e) {
            if (transaction != null) transactions.rollback(transaction);
            lock.unlock();
            throw e;
        }
        request.setAttribute(VIEW, new View(transaction, lock));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(VIEW) instanceof View view) {
            request.removeAttribute(VIEW);
            try {
                transactions.rollback(view.transaction());
            } finally {
                view.lock().unlock();
            }
            return;
        }
        if (isRead(request) || ex != null || response.getStatus() >= 400) return;
        String sheetId = sheetId(request);
        if (sheetId != null) request.setAttribute(SheetHistoryFilter.CHANGED, sheetId);
    }

    private static boolean isRead(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
    }

    /** The sheet the request is for: its sheetId path variable, or the id of a request to the sheet itself. */
    private static String sheetId(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return null;
        return vars.getOrDefault("sheetId", vars.get("id"));
    }
}
