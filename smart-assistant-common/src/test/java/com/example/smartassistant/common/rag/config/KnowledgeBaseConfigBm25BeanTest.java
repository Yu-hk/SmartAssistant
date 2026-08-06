package com.example.smartassistant.common.rag.config;

import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseConfigBm25BeanTest {

    @Test
    void createsDefaultBm25Scorer() {
        Bm25Scorer scorer = new KnowledgeBaseConfig().bm25Scorer(new ChineseTokenizer());

        assertThat(scorer).isNotNull();
        assertThat(scorer.isInitialized()).isFalse();
    }

    @Test
    void usesModuleSpecificBeanName() throws Exception {
        Bean bean = KnowledgeBaseConfig.class
                .getDeclaredMethod("bm25Scorer", ChineseTokenizer.class)
                .getAnnotation(Bean.class);

        assertThat(bean.value()).containsExactly("commonBm25Scorer");
    }
}
