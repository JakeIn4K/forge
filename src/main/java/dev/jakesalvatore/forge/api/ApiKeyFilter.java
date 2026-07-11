package dev.jakesalvatore.forge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a valid X-API-Key header on every /api/** request. Deliberately
 * not Spring Security: one static header check doesn't justify a framework,
 * and OAuth would be the production choice anyway (documented in README).
 * Actuator endpoints stay open for health probes and metrics scraping.
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String REQUEST_ATTRIBUTE = "forge.apiKey";

    private final ApiProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !properties.keys().contains(key)) {
            writeProblem(response);
            return;
        }
        request.setAttribute(REQUEST_ATTRIBUTE, key);
        chain.doFilter(request, response);
    }

    private void writeProblem(HttpServletResponse response) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "missing or invalid " + HEADER + " header");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // the output stream, not the writer: the writer appends a charset to
        // the content type; jackson emits UTF-8 either way
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
