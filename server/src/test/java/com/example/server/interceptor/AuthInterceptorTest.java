package com.example.server.interceptor;

import com.example.server.utils.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * This interceptor is the single gate in front of /media/** and /debug/**, and it
 * is where the userId every ownership check relies on is established. If it lets
 * an unauthenticated request through, or trusts anything the client can set,
 * every downstream authorization check is void.
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthInterceptor authInterceptor;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("A valid Bearer token is accepted and the resolved uid is attached")
    void validTokenIsAcceptedAndUidAttached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/list");
        request.addHeader("Authorization", "Bearer good.token.here");
        when(jwtUtils.parseUserId("good.token.here")).thenReturn(42L);

        boolean proceed = authInterceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        // Controllers read the uid from here, never from a request parameter
        assertThat(request.getAttribute(AuthInterceptor.ATTR_UID)).isEqualTo(42L);
        assertThat(AuthInterceptor.currentUserId(request)).isEqualTo(42L);
    }

    @Test
    @DisplayName("A request with no Authorization header is rejected with 401")
    void missingHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/list");
        when(jwtUtils.parseUserId(isNull())).thenReturn(null);

        boolean proceed = authInterceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized");
        // Nothing may be attached, or a controller could read a stale/absent uid
        assertThat(request.getAttribute(AuthInterceptor.ATTR_UID)).isNull();
    }

    @Test
    @DisplayName("An invalid or expired token is rejected with 401")
    void invalidTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/debug/ai");
        request.addHeader("Authorization", "Bearer tampered.or.expired");
        when(jwtUtils.parseUserId(anyString())).thenReturn(null);

        boolean proceed = authInterceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute(AuthInterceptor.ATTR_UID)).isNull();
    }

    @Test
    @DisplayName("A header without the Bearer prefix is not treated as a token")
    void nonBearerHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/list");
        // e.g. Basic auth, or a raw token pasted without the scheme
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        when(jwtUtils.parseUserId(isNull())).thenReturn(null);

        boolean proceed = authInterceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("CORS preflight (OPTIONS) passes through without a token")
    void optionsPreflightIsAllowed() throws Exception {
        // The browser sends OPTIONS without Authorization; blocking it would break
        // every cross-origin call from the SPA before the real request is made.
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/media/upload");

        boolean proceed = authInterceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        // Preflight is not authenticated, so no uid is established either
        assertThat(request.getAttribute(AuthInterceptor.ATTR_UID)).isNull();
    }

    @Test
    @DisplayName("currentUserId is null when the interceptor never ran")
    void currentUserIdIsNullWithoutInterceptor() {
        assertThat(AuthInterceptor.currentUserId(new MockHttpServletRequest())).isNull();
    }
}
