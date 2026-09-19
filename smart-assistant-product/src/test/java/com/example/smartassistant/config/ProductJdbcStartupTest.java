package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductJdbcStartupTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,JdbcTemplateAutoConfiguration.class))
        .withUserConfiguration(RagProductionAutoConfiguration.class)
        .withBean(BgeEmbeddingModel.class,()->mock(BgeEmbeddingModel.class))
        .withBean(ChineseTokenizer.class,()->mock(ChineseTokenizer.class))
        .withPropertyValues("app.rag.store.mode=memory",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/synthetic_unreachable",
            "spring.datasource.username=fixture","spring.datasource.password=fixture");

    @Test void standardSpringDatasourceCreatesBothTemplatesWithoutLegacyNullBean() {
        runner.run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(JdbcTemplate.class).hasSingleBean(NamedParameterJdbcTemplate.class);
            assertThat(context).doesNotHaveBean("ragProductionJdbcTemplate");
        });
    }
    @Test void unreachableLegacyDatasourceStillProvidesANonNullTemplate() {
        runner.withPropertyValues("datasource.url=jdbc:postgresql://127.0.0.1:1/synthetic_unreachable",
            "datasource.username=fixture","datasource.password=fixture").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(JdbcTemplate.class).hasSingleBean(NamedParameterJdbcTemplate.class);
            assertThat(context.getBean("ragProductionJdbcTemplate")).isInstanceOf(JdbcTemplate.class);
        });
    }
}
