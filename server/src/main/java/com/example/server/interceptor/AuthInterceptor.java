package com.example.server.interceptor;

import com.example.server.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Auth interceptor: validates "Authorization: Bearer <token>" and stores the resolved
 * userId as a request attribute, so controllers trust the token instead of a client-supplied param.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_UID = "authUserId";

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Let CORS preflight (OPTIONS) through
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
        Long uid = jwtUtils.parseUserId(token);

        if (uid == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Unauthorized: please sign in\"}");
            return false;
        }

        request.setAttribute(ATTR_UID, uid);
        return true;
    }

    /**
     * Read the authenticated userId from the request.
     */
    public static Long currentUserId(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_UID);
        return v == null ? null : (Long) v;
    }
}
