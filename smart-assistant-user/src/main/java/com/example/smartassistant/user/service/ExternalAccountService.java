package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserExternalIdentityMapper;
import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.UserExternalIdentity;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.oauth.ExternalOAuthIdentity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ExternalAccountService {
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern GENERATED_USERNAME = Pattern.compile("^(wx|dd|fs)_[0-9a-f]{24}$");

    private final UserExternalIdentityMapper identityMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public ExternalAccountService(UserExternalIdentityMapper identityMapper, UserMapper userMapper,
                                  PasswordEncoder passwordEncoder, AuthService authService) {
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    @Transactional
    public AuthResponse login(ExternalOAuthIdentity external, String ipAddress, String userAgent) {
        String provider = external.provider().id();
        identityMapper.lockIdentity(provider + ":" + external.subject());
        UserExternalIdentity identity = identityMapper.find(provider, external.subject());
        User user;
        if (identity == null) {
            user = createUser(external);
            identity = new UserExternalIdentity();
            identity.setUserId(user.getId());
            identity.setProvider(provider);
            identity.setSubject(external.subject());
            copyProfile(identity, external);
            identityMapper.insert(identity);
        } else {
            user = userMapper.selectById(identity.getUserId());
            migrateGeneratedUsername(user, external);
            if (profileChanged(identity, external)) {
                copyProfile(identity, external);
                identityMapper.updateById(identity);
            }
        }
        if (user == null) throw new IllegalStateException("第三方身份关联的用户不存在");
        return authService.issueForUser(user, ipAddress, userAgent);
    }

    private User createUser(ExternalOAuthIdentity external) {
        User user = new User();
        user.setUsername(resolveUsername(external));
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEmail(blankToNull(external.email()));
        user.setRole("ROLE_USER");
        userMapper.insert(user);
        return user;
    }

    private void migrateGeneratedUsername(User user, ExternalOAuthIdentity external) {
        if (user == null || user.getUsername() == null
                || !GENERATED_USERNAME.matcher(user.getUsername()).matches()) return;
        String displayName = sanitizeDisplayName(external.displayName());
        if (displayName == null) return;
        String username = resolveUsername(external);
        if (user.getUsername().equals(username)) return;
        user.setUsername(username);
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.updateById(user);
    }

    private String resolveUsername(ExternalOAuthIdentity external) {
        String displayName = sanitizeDisplayName(external.displayName());
        if (displayName != null && !userMapper.existsByUsername(displayName)) return displayName;

        String digest = digest(external.provider().id() + ":" + external.subject());
        if (displayName != null) {
            String suffix = "_" + providerPrefix(external) + "_" + digest.substring(0, 8);
            String candidate = truncate(displayName, MAX_USERNAME_LENGTH - suffix.length()) + suffix;
            if (!userMapper.existsByUsername(candidate)) return candidate;
        }

        String generated = providerPrefix(external) + "_" + digest.substring(0, 24);
        if (!userMapper.existsByUsername(generated)) return generated;
        return providerPrefix(external) + "_" + digest.substring(0, 40);
    }

    private String sanitizeDisplayName(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = INVISIBLE_CHARACTERS.matcher(normalized).replaceAll("");
        normalized = REPEATED_WHITESPACE.matcher(normalized.trim()).replaceAll(" ");
        return normalized.isBlank() ? null : truncate(normalized, MAX_USERNAME_LENGTH);
    }

    private String providerPrefix(ExternalOAuthIdentity external) {
        return switch (external.provider()) {
            case WECHAT -> "wx";
            case DINGTALK -> "dd";
            case FEISHU -> "fs";
        };
    }

    private String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private void copyProfile(UserExternalIdentity target, ExternalOAuthIdentity source) {
        target.setUnionId(blankToNull(source.unionId()));
        target.setDisplayName(blankToNull(source.displayName()));
        target.setAvatarUrl(blankToNull(source.avatarUrl()));
        target.setEmail(blankToNull(source.email()));
        if (target.getId() != null) target.setUpdatedAt(java.time.LocalDateTime.now());
    }

    private boolean profileChanged(UserExternalIdentity current, ExternalOAuthIdentity incoming) {
        return !java.util.Objects.equals(current.getUnionId(), blankToNull(incoming.unionId()))
                || !java.util.Objects.equals(current.getDisplayName(), blankToNull(incoming.displayName()))
                || !java.util.Objects.equals(current.getAvatarUrl(), blankToNull(incoming.avatarUrl()))
                || !java.util.Objects.equals(current.getEmail(), blankToNull(incoming.email()));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
