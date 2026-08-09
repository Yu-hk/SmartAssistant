package com.example.smartassistant.user.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMapperConfigurationTest {

    @Test
    void findByUsernameSelectsStoredRole() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/mapper/UserMapper.xml")) {
            assertNotNull(input);
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statementStart = mapperXml.indexOf("<select id=\"findByUsername\"");
            int statementEnd = mapperXml.indexOf("</select>", statementStart);
            String statement = mapperXml.substring(statementStart, statementEnd);

            assertTrue(statement.matches("(?s).*SELECT\\s+.*\\brole\\b.*FROM\\s+users.*"));
        }
    }
}
