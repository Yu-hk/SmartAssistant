package com.example.smartassistant.consumer.service.prompt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StageAwarePromptServiceTest {
    @Test void collectionPromptDoesNotInventBusinessRequiredFields() {
        var service = new StageAwarePromptService(new StageAwarePromptService.PromptProperties());
        service.init();
        var prompt = service.buildFocusedPrompt(StageAwarePromptService.DialogStage.COLLECTING, "PRODUCT");
        assertThat(prompt).contains("业务执行服务", "不自行推断", "不重复索要")
                .doesNotContain("请提供订单号", "预算、类型", "全部槽位");
    }
}
