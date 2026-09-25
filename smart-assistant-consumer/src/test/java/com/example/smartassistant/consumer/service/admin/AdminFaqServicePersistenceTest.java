package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.consumer.infrastructure.db.DatabaseDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminFaqServicePersistenceTest {
    private AdminService admin;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:admin-faq-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        AdminService target = new AdminService(jdbc, new DatabaseDialect.PostgresDialect());
        target.initializePersistentAdminStorage();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),
                new AnnotationTransactionAttributeSource()));
        admin = (AdminService) factory.getProxy();
    }

    @Test
    void faqLifecycleKeepsExistingAdminServiceContract() {
        assertThat(admin.getFaqs()).hasSize(4);
        AdminService.FaqItem created = admin.createFaq(Map.of(
                "category", "product", "question", "新商品怎么查？", "answer", "请描述商品名称或需求。"));
        assertThat(created.sourceType()).isEqualTo("manual");
        assertThat(created.hitCount()).isZero();

        assertThat(admin.hitFaq(created.id())).isTrue();
        AdminService.FaqItem updated = admin.updateFaq(created.id(), Map.of("answer", "请提供品类或使用需求。"));
        assertThat(updated.answer()).isEqualTo("请提供品类或使用需求。");
        assertThat(updated.hitCount()).isEqualTo(1);
        assertThatThrownBy(() -> admin.createFaq(Map.of("question", "新商品怎么查？", "answer", "重复")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(admin.deleteFaq(created.id())).isTrue();
        assertThat(admin.deleteFaq(created.id())).isFalse();
        assertThat(admin.getFaqs()).hasSize(4);
    }

    @Test
    void importPreservesProvenanceAndOverwriteChoice() {
        AdminService.FaqImportResult imported = admin.importFaqs("qa.csv", "csv", false,
                List.of(Map.of("question", "文档导入如何操作？", "answer", "打开文档导入页。")));
        assertThat(imported).isEqualTo(new AdminService.FaqImportResult(1, 1, 0, 0));
        assertThat(admin.importFaqs("qa.csv", "csv", false,
                List.of(Map.of("question", "文档导入如何操作？", "answer", "不覆盖"))).skipped()).isEqualTo(1);
        assertThat(admin.importFaqs("qa.json", "json", true,
                List.of(Map.of("question", "文档导入如何操作？", "answer", "先选择文件再导入。"))).updated()).isEqualTo(1);
        AdminService.FaqItem item = admin.getFaqs().stream()
                .filter(faq -> faq.question().equals("文档导入如何操作？"))
                .findFirst().orElseThrow();
        assertThat(item.answer()).isEqualTo("先选择文件再导入。");
        assertThat(item.sourceName()).isEqualTo("qa.json");
        assertThat(item.sourceType()).isEqualTo("json");
    }

    @Test
    void invalidLaterImportItemRollsBackEarlierInsert() {
        assertThatThrownBy(() -> admin.importFaqs("qa.csv", "csv", false,
                List.of(Map.of("question", "先插入", "answer", "应回滚"),
                        Map.of("question", "后续无答案"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.getFaqs()).hasSize(4);
    }
}
