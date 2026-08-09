package com.example.smartassistant.user.service;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.CurrentUserResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private SessionService sessionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userMapper,
                passwordEncoder,
                jwtService,
                authenticationManager,
                sessionService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void badCredentialsAreMappedToUnauthorized() {
        LoginRequest request = new LoginRequest("alice", "bad-password");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> authService.login(request, "127.0.0.1", "JUnit"));

        assertEquals(401, exception.getHttpStatus());
        assertEquals("AUTH_ERROR", exception.getErrorCode());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void duplicateUsernameIsMappedToConflict() {
        RegisterRequest request = new RegisterRequest("alice", "password", "alice@example.com");
        when(userMapper.existsByUsername("alice")).thenReturn(true);

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> authService.register(request));

        assertEquals(409, exception.getHttpStatus());
        assertEquals("USERNAME_CONFLICT", exception.getErrorCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void loginResponseIncludesStoredRoleAndCreatesSession() {
        LoginRequest request = new LoginRequest("admin", "password");
        User user = user(7L, "admin", "admin@example.com", "ROLE_ADMIN");
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userMapper.findByUsername("admin")).thenReturn(user);
        when(jwtService.generateToken(7L, "admin", "ROLE_ADMIN")).thenReturn("access-token");
        when(jwtService.generateRefreshToken("admin")).thenReturn("refresh-token");
        when(jwtService.extractTokenId("access-token")).thenReturn("access-jti");

        AuthResponse response = authService.login(request, "10.0.0.2", "JUnit");

        assertEquals("ROLE_ADMIN", response.getRole());
        assertEquals(7L, response.getUserId());
        verify(sessionService).createSession(7L, "access-jti", "10.0.0.2", "JUnit");
    }

    @Test
    void refreshRejectsAnAccessToken() {
        when(jwtService.extractUsername("access-token")).thenReturn("alice");
        when(jwtService.validateRefreshToken("access-token", "alice")).thenReturn(false);

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> authService.refreshToken("access-token"));

        assertEquals(401, exception.getHttpStatus());
        assertEquals("INVALID_REFRESH_TOKEN", exception.getErrorCode());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void refreshReturnsSameAuthContractAndCreatesNewAccessSession() {
        User user = user(8L, "alice", "alice@example.com", null);
        Duration refreshTtl = Duration.ofHours(24);
        when(jwtService.extractUsername("refresh-token")).thenReturn("alice");
        when(jwtService.validateRefreshToken("refresh-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("refresh-token")).thenReturn("refresh-jti");
        when(jwtService.getRefreshBlacklistTtl()).thenReturn(refreshTtl);
        when(sessionService.consumeRefreshToken("refresh-jti", refreshTtl)).thenReturn(true);
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(jwtService.generateToken(8L, "alice", "ROLE_USER")).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken("alice")).thenReturn("new-refresh-token");
        when(jwtService.extractTokenId("new-access-token")).thenReturn("new-access-jti");

        AuthResponse response = authService.refreshToken("refresh-token", "10.0.0.3", "JUnit");

        assertEquals("new-access-token", response.getToken());
        assertEquals("new-refresh-token", response.getRefreshToken());
        assertEquals("ROLE_USER", response.getRole());
        verify(sessionService).createSession(8L, "new-access-jti", "10.0.0.3", "JUnit");
    }

    @Test
    void refreshRejectsAlreadyConsumedRefreshToken() {
        Duration refreshTtl = Duration.ofHours(24);
        when(jwtService.extractUsername("refresh-token")).thenReturn("alice");
        when(jwtService.validateRefreshToken("refresh-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("refresh-token")).thenReturn("refresh-jti");
        when(jwtService.getRefreshBlacklistTtl()).thenReturn(refreshTtl);
        when(sessionService.consumeRefreshToken("refresh-jti", refreshTtl)).thenReturn(false);

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> authService.refreshToken("refresh-token"));

        assertEquals(401, exception.getHttpStatus());
        assertEquals("INVALID_REFRESH_TOKEN", exception.getErrorCode());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void currentUserUsesSafeProjectionWithoutPassword() {
        User user = user(9L, "alice", "alice@example.com", "ROLE_USER");
        user.setPassword("password-hash");
        when(jwtService.extractUsername("access-token")).thenReturn("alice");
        when(jwtService.validateAccessToken("access-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("access-token")).thenReturn("access-jti");
        when(sessionService.validateSession("access-jti")).thenReturn(true);
        when(jwtService.extractUserId("access-token")).thenReturn(9L);
        when(userMapper.selectById(9L)).thenReturn(user);

        CurrentUserResponse response = authService.getCurrentUser("access-token");

        assertEquals(9L, response.userId());
        assertEquals("alice", response.username());
        assertEquals(4, CurrentUserResponse.class.getRecordComponents().length);
    }

    @Test
    void logoutRevokesSessionAndWritesGatewayBlacklistContract() {
        Duration remaining = Duration.ofMinutes(30);
        when(jwtService.extractUsername("access-token")).thenReturn("alice");
        when(jwtService.validateAccessToken("access-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("access-token")).thenReturn("access-jti");
        when(jwtService.getRemainingValidity("access-token")).thenReturn(remaining);

        authService.logout("access-token");

        verify(sessionService).revokeAccessToken("access-jti", remaining);
    }

    @Test
    void logoutRevokesAccessAndRefreshTokensForTheSameUser() {
        Duration accessRemaining = Duration.ofMinutes(30);
        Duration refreshTtl = Duration.ofHours(24);
        when(jwtService.extractUsername("access-token")).thenReturn("alice");
        when(jwtService.validateAccessToken("access-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("access-token")).thenReturn("access-jti");
        when(jwtService.extractUsername("refresh-token")).thenReturn("alice");
        when(jwtService.validateRefreshToken("refresh-token", "alice")).thenReturn(true);
        when(jwtService.extractTokenId("refresh-token")).thenReturn("refresh-jti");
        when(jwtService.getRefreshBlacklistTtl()).thenReturn(refreshTtl);
        when(jwtService.getRemainingValidity("access-token")).thenReturn(accessRemaining);

        authService.logout("access-token", "refresh-token");

        verify(sessionService).blacklistRefreshToken("refresh-jti", refreshTtl);
        verify(sessionService).revokeAccessToken("access-jti", accessRemaining);
    }

    private User user(Long id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(role);
        user.setPassword("password-hash");
        return user;
    }
}
