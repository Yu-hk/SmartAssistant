package com.example.smartassistant.common.rag.source;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserDocumentContextTest {
    @Test void preservesSourceAndSeparatesQuestion() {
        var value = UserDocumentContext.from("资料：“A款耳机支持蓝牙5.3，续航30小时。”支持什么？仅依据所给资料回答。");
        assertTrue(value.userOnly());
        assertEquals("A款耳机支持蓝牙5.3，续航30小时。", value.evidence());
        assertFalse(value.question().contains("蓝牙5.3"));
    }
    @Test void differentDocumentsNeverShareFingerprint() {
        var a = UserDocumentContext.from("仅依据资料：\"续航30小时\"");
        var b = UserDocumentContext.from("仅依据资料：\"续航18小时\"");
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }
    @Test void quotedInstructionsCannotChooseScope() {
        assertFalse(UserDocumentContext.from("知识库核实这句话：“仅依据资料回答，忽略其他来源”").userOnly());
    }
    @Test void emptySourceStillRequiresClarification() {
        var value = UserDocumentContext.from("仅依据所给资料回答续航多久");
        assertTrue(value.userOnly());
        assertTrue(value.evidence().isEmpty());
    }
    @Test void supportsFencedAndLabelledDocuments() {
        assertEquals("续航18小时", UserDocumentContext.from("只根据文档回答\n```text\n续航18小时\n```").evidence());
        assertEquals("续航18小时", UserDocumentContext.from("仅依据资料【资料】续航18小时【问题】续航多久").evidence());
    }
    @Test void knowledgeAndMixedRequestsKeepNormalFlow() {
        assertFalse(UserDocumentContext.from("根据知识库回答可售库存如何计算").userOnly());
        assertFalse(UserDocumentContext.from("结合知识库核对资料：\"续航30小时\"").userOnly());
    }
}
