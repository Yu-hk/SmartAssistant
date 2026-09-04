package com.example.smartassistant.consumer.config;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDialectConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseDialectConfiguration.class);

    @Test
    void createsPostgresDialectFromJdbcUrl() {
        contextRunner
                .withBean(DataSourceProperties.class, () -> properties("jdbc:postgresql://localhost/app"))
                .run(context -> assertThat(context.getBean(DatabaseDialect.class))
                        .isInstanceOf(DatabaseDialect.PostgresDialect.class));
    }

    @Test
    void createsMysqlDialectFromJdbcUrl() {
        contextRunner
                .withBean(DataSourceProperties.class, () -> properties("jdbc:mysql://localhost/app"))
                .run(context -> assertThat(context.getBean(DatabaseDialect.class))
                        .isInstanceOf(DatabaseDialect.MySQLDialect.class));
    }

    @Test
    void defaultsToPostgresBeforeDataSourceAutoConfigurationCreatesProperties() {
        contextRunner.run(context -> assertThat(context.getBean(DatabaseDialect.class))
                .isInstanceOf(DatabaseDialect.PostgresDialect.class));
    }

    @Test
    void backsOffWhenApplicationProvidesDialect() {
        DatabaseDialect custom = new DatabaseDialect.PostgresDialect();
        contextRunner
                .withBean(DataSourceProperties.class, () -> properties("jdbc:mysql://localhost/app"))
                .withBean(DatabaseDialect.class, () -> custom)
                .run(context -> assertThat(context.getBean(DatabaseDialect.class)).isSameAs(custom));
    }

    private DataSourceProperties properties(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        return properties;
    }
}
