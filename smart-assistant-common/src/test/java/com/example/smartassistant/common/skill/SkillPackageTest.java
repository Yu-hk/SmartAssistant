package com.example.smartassistant.common.skill;

import com.example.smartassistant.common.prompt.PromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillPackage} 和 {@link SkillPackageManager} 单元测试。
 */
class SkillPackageTest {

    @Test
    @DisplayName("创建技能包应保留属性")
    void createSkillPackage() {
        SkillPackage pkg = SkillPackage.builder("test-skill", "测试技能")
                .description("一个测试技能")
                .version("1.0.0")
                .instruction("当用户问价格时，先查库存再报价。")
                .addTag("product")
                .addTag("price")
                .build();

        assertEquals("test-skill", pkg.getId());
        assertEquals("测试技能", pkg.getName());
        assertEquals("1.0.0", pkg.getVersion());
        assertTrue(pkg.isEnabled());
        assertTrue(pkg.getTags().contains("product"));
    }

    @Test
    @DisplayName("构建注入 prompt 应包含指令内容")
    void buildInjectionPrompt() {
        SkillPackage pkg = SkillPackage.builder("test", "测试")
                .instruction("请按以下步骤操作...")
                .build();

        String prompt = pkg.buildInjectionPrompt();
        assertTrue(prompt.contains("测试"));
        assertTrue(prompt.contains("请按以下步骤操作..."));
    }

    @Test
    @DisplayName("禁用时注入 prompt 应为空")
    void disabledPackageEmptyPrompt() {
        SkillPackage pkg = SkillPackage.builder("test", "测试")
                .instruction("指令内容")
                .build();
        pkg.setEnabled(false);
        assertTrue(pkg.buildInjectionPrompt().isBlank());
    }

    @Test
    @DisplayName("支持文件和标签")
    void supportingFilesAndTags() {
        SkillPackage pkg = SkillPackage.builder("test", "测试")
                .instruction("指令")
                .addFile("ref.md", "# 参考文档")
                .addTag("tag1")
                .build();

        assertEquals(1, pkg.getSupportingFiles().size());
        assertTrue(pkg.getSupportingFiles().containsKey("ref.md"));
        assertTrue(pkg.getTags().contains("tag1"));
    }
}

class SkillPackageManagerTest {

    @Test
    @DisplayName("注册和获取技能包")
    void registerAndGet() {
        SkillPackageManager mgr = new SkillPackageManager();
        SkillPackage pkg = SkillPackage.builder("test", "测试")
                .instruction("指令").build();
        mgr.register(pkg);

        assertTrue(mgr.get("test").isPresent());
        assertEquals("测试", mgr.get("test").get().getName());
    }

    @Test
    @DisplayName("绑定技能包到 Agent")
    void bindToAgent() {
        SkillPackageManager mgr = new SkillPackageManager();
        SkillPackage pkg = SkillPackage.builder("s1", "技能1")
                .instruction("指令").build();
        mgr.register(pkg);
        mgr.bind("s1", "agent1");

        assertEquals(1, mgr.getAgentSkills("agent1").size());
        assertTrue(mgr.getAgentSkills("agent1").get(0).isBoundTo("agent1"));
    }

    @Test
    @DisplayName("解绑后 Agent 技能列表应为空")
    void unbindFromAgent() {
        SkillPackageManager mgr = new SkillPackageManager();
        SkillPackage pkg = SkillPackage.builder("s1", "技能1")
                .instruction("指令").build();
        mgr.register(pkg);
        mgr.bind("s1", "agent1");
        mgr.unbind("s1", "agent1");

        assertTrue(mgr.getAgentSkills("agent1").isEmpty());
    }

    @Test
    @DisplayName("注销后技能包应不可获取")
    void unregisterRemovesPackage() {
        SkillPackageManager mgr = new SkillPackageManager();
        SkillPackage pkg = SkillPackage.builder("s1", "技能1")
                .instruction("指令").build();
        mgr.register(pkg);
        mgr.unregister("s1");

        assertTrue(mgr.get("s1").isEmpty());
    }

    @Test
    @DisplayName("禁用技能包不应出现在 Agent prompt 中")
    void disabledNotInPrompt() {
        SkillPackageManager mgr = new SkillPackageManager();
        SkillPackage pkg = SkillPackage.builder("s1", "禁用技能")
                .instruction("指令内容").build();
        pkg.setEnabled(false);
        mgr.register(pkg);
        mgr.bind("s1", "agent1");

        assertTrue(mgr.buildAgentSkillPrompt("agent1").isBlank());
    }

    @Test
    @DisplayName("无绑定时 prompt 应为空")
    void noBindingsPromptEmpty() {
        SkillPackageManager mgr = new SkillPackageManager();
        assertTrue(mgr.buildAgentSkillPrompt("nonexistent").isBlank());
    }

    @Test
    @DisplayName("getAll 应返回所有注册的包")
    void getAllReturnsAll() {
        SkillPackageManager mgr = new SkillPackageManager();
        mgr.register(SkillPackage.builder("a", "A").instruction("").build());
        mgr.register(SkillPackage.builder("b", "B").instruction("").build());
        assertEquals(2, mgr.getAll().size());
    }
}
