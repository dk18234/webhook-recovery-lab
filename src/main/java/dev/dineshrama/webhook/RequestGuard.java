package dev.dineshrama.webhook;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
    public static final int MAX_BODY=16384;
    private final LabProperties config;
    public RequestGuard(LabProperties config) { this.config=config; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","same-origin");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        String path=request.getRequestURI();
        if((path.startsWith("/api/") || path.startsWith("/actuator/")) && !path.equals("/actuator/health") && !Signatures.constantEquals(config.adminToken(),request.getHeader("X-Admin-Token"))) {
            reject(response,401,"ADMIN_TOKEN_REQUIRED","Enter the admin token to inspect or operate the lab.");return;
        }
        if((path.startsWith("/api/") || path.startsWith("/webhooks/")) && (request.getMethod().equals("POST") || request.getMethod().equals("PUT"))) {
            byte[] body=request.getInputStream().readNBytes(MAX_BODY+1);
            if(body.length>MAX_BODY) { reject(response,413,"PAYLOAD_TOO_LARGE","Request body must be at most 16 KiB.");return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var stream=new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() { return stream.read(); }
                        public boolean isFinished() { return stream.available()==0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body only."); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
            },response);
        } else chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
