package com.example.smartassistant.service.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProductPublicAnswerTest {
    @Test void removesCapturedOnlineParentheticalButKeepsModelAndFacts() {
        assertThat(ProductPublicAnswer.format("您好，MacBook Air M3（商品编码 MACBOOK-AIR-M3）目前库存紧张，售价8999元。"))
                .isEqualTo("您好，MacBook Air M3目前库存紧张，售价8999元。");
    }

    @Test void removesMarkdownAndInlineCodeFields() {
        assertThat(ProductPublicAnswer.format("AirPods Pro（第二代）\n- **商品编码**：`AIRPODS-PRO`\n售价1999元，库存充足。"))
                .isEqualTo("AirPods Pro（第二代）\n售价1999元，库存充足。");
        assertThat(ProductPublicAnswer.format("商品编号：12345，售价1999元。"))
                .isEqualTo("售价1999元。");
        assertThat(ProductPublicAnswer.format("AirPods Pro (SKU: AIRPODS-PRO) 有货。"))
                .isEqualTo("AirPods Pro 有货。");
    }

    @Test void leavesNamesSpecsOrderNumbersAndEvidenceIntact() {
        String text = "AirPods Pro（第二代），MacBook Air M3，USB-C，16GB，1999元；订单号 ORD-123。[E2]";
        assertThat(ProductPublicAnswer.format(text)).isEqualTo(text);
        assertThat(ProductPublicAnswer.format(null)).isNull();
    }
}
