package com.example.smartassistant.intake.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Full replacement of an administrator-maintained alias list. */
public record ProductAliasUpdate(long expectedRevision, List<String> aliases) {
    public static ProductAliasUpdate parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2
                || !body.path("expectedRevision").canConvertToLong()
                || !body.has("aliases")) throw bad("请提供修订号和别名列表");
        long revision = body.path("expectedRevision").longValue();
        if (revision < 0) throw bad("修订号不正确");
        return new ProductAliasUpdate(revision, aliases(body.get("aliases")));
    }

    public static List<String> aliases(JsonNode values) {
        if (values == null || !values.isArray() || values.size() > 10) throw bad("别名最多 10 个");
        List<String> aliases = new ArrayList<>();
        Set<String> normalized = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw bad("别名必须为文本");
            String alias = value.textValue().trim();
            if (alias.length() < 2 || alias.length() > 200
                    || alias.chars().anyMatch(Character::isISOControl)) throw bad("别名长度或格式不正确");
            if (!normalized.add(alias.toUpperCase(Locale.ROOT))) throw bad("别名不能重复");
            aliases.add(alias);
        }
        return List.copyOf(aliases);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
