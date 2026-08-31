package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserExternalIdentityMapper;
import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.UserExternalIdentity;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.oauth.ExternalOAuthIdentity;
import com.example.smartassistant.user.oauth.OAuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalAccountServiceTest {
    @Mock UserExternalIdentityMapper identityMapper;
    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthService authService;
    private ExternalAccountService service;

    @BeforeEach
    void setUp() {
        service = new ExternalAccountService(identityMapper, userMapper, passwordEncoder, authService);
    }

    @Test
    void firstLoginCreatesLocalUserAndIdentity() {
        ExternalOAuthIdentity external = new ExternalOAuthIdentity(
                OAuthProvider.WECHAT, "openid-1", "union-1", "测试用户", null, "avatar");
        when(identityMapper.find("wechat", "openid-1")).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(42L);
            return 1;
        });
        AuthResponse expected = new AuthResponse("token", "refresh", 42L, "user", null, "ROLE_USER");
        when(authService.issueForUser(any(), any(), any())).thenReturn(expected);

        AuthResponse actual = service.login(external, "127.0.0.1", "JUnit");

        assertEquals(expected, actual);
        verify(userMapper).insert(org.mockito.ArgumentMatchers.<User>argThat(
                user -> "测试用户".equals(user.getUsername())));
        verify(identityMapper).insert(any(UserExternalIdentity.class));
        verify(identityMapper).lockIdentity("wechat:openid-1");
    }

    @Test
    void repeatedLoginReusesLinkedUser() {
        UserExternalIdentity identity = new UserExternalIdentity();
        identity.setId(3L);
        identity.setUserId(42L);
        identity.setProvider("dingtalk");
        identity.setSubject("open-2");
        identity.setDisplayName("Alice");
        User user = new User();
        user.setId(42L);
        user.setUsername("dd_existing");
        when(identityMapper.find("dingtalk", "open-2")).thenReturn(identity);
        when(userMapper.selectById(42L)).thenReturn(user);
        when(authService.issueForUser(user, "127.0.0.1", "JUnit"))
                .thenReturn(new AuthResponse("token", "refresh", 42L, "dd_existing", null, "ROLE_USER"));

        AuthResponse actual = service.login(new ExternalOAuthIdentity(
                OAuthProvider.DINGTALK, "open-2", null, "Alice", null, null),
                "127.0.0.1", "JUnit");

        assertEquals(42L, actual.getUserId());
        verify(userMapper, never()).insert(any(User.class));
        verify(identityMapper, never()).insert(any(UserExternalIdentity.class));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void duplicatePlatformNameGetsStableProviderSuffix() {
        ExternalOAuthIdentity external = new ExternalOAuthIdentity(
                OAuthProvider.DINGTALK, "open-duplicate", null, "Alice", null, null);
        when(identityMapper.find("dingtalk", "open-duplicate")).thenReturn(null);
        when(userMapper.existsByUsername("Alice")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(43L);
            return 1;
        });
        when(authService.issueForUser(any(), any(), any()))
                .thenReturn(new AuthResponse("token", "refresh", 43L, "Alice_dd_suffix", null, "ROLE_USER"));

        service.login(external, "127.0.0.1", "JUnit");

        verify(userMapper).insert(org.mockito.ArgumentMatchers.<User>argThat(user ->
                user.getUsername().matches("Alice_dd_[0-9a-f]{8}")));
    }

    @Test
    void existingGeneratedUsernameMigratesToPlatformName() {
        UserExternalIdentity identity = new UserExternalIdentity();
        identity.setId(4L);
        identity.setUserId(44L);
        identity.setProvider("feishu");
        identity.setSubject("open-4");
        User user = new User();
        user.setId(44L);
        user.setUsername("fs_0123456789abcdef01234567");
        when(identityMapper.find("feishu", "open-4")).thenReturn(identity);
        when(userMapper.selectById(44L)).thenReturn(user);
        when(authService.issueForUser(any(), any(), any()))
                .thenReturn(new AuthResponse("token", "refresh", 44L, "飞书用户", null, "ROLE_USER"));

        service.login(new ExternalOAuthIdentity(
                OAuthProvider.FEISHU, "open-4", null, "飞书用户", null, null),
                "127.0.0.1", "JUnit");

        verify(userMapper).updateById(org.mockito.ArgumentMatchers.<User>argThat(
                updated -> "飞书用户".equals(updated.getUsername())));
        assertEquals("飞书用户", user.getUsername());
    }

    @Test
    void blankPlatformNameFallsBackToGeneratedUsername() {
        ExternalOAuthIdentity external = new ExternalOAuthIdentity(
                OAuthProvider.WECHAT, "open-blank", null, " \u0000 ", null, null);
        when(identityMapper.find("wechat", "open-blank")).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(45L);
            return 1;
        });
        when(authService.issueForUser(any(), any(), any()))
                .thenReturn(new AuthResponse("token", "refresh", 45L, "generated", null, "ROLE_USER"));

        service.login(external, "127.0.0.1", "JUnit");

        verify(userMapper).insert(org.mockito.ArgumentMatchers.<User>argThat(user -> {
            assertTrue(user.getUsername().matches("wx_[0-9a-f]{24}"));
            return true;
        }));
    }
}
