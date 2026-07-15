# 贡献指南（CONTRIBUTING）

感谢参与 **SmartAssistant** 多智能体 AI 旅行规划平台的开发。本文档说明分支策略、提交规范与 CI 卡点，确保你的改动能顺利合入 `main`。

## 1. 开发前置

- **JDK 21**（Temurin 21.0.6+）、**Maven 3.9+**
- **Node 22+**（前端）
- **Docker**（本地编排 Postgres / Redis / Nacos / Grafana / Prometheus / Alertmanager）
- 详见 [`docs/local-setup.md`](docs/local-setup.md)

## 2. 分支策略

| 分支 | 用途 | 保护规则 |
|------|------|----------|
| `main` | 受保护主干，禁止直推 | GitHub Ruleset：要求 `eval-gate` + `quality-gate` 两个 status check 通过 |
| `phaseN-xxx` | 功能/阶段特性分支 | 从 `main` 切出，开 PR 合入 |

> ⚠️ **禁止直推 `main`**。所有改动必须经 PR，且 CI 两个 required check 全绿后方可合并（与 Ruleset 一致）。

标准流程：

```bash
git checkout -b phase3-evolution main      # 从 main 切特性分支
# ... 编码、本地测试 ...
git push -u origin phase3-evolution
# 在 GitHub 打开 PR → 等 CI 变绿 → 合并
```

## 3. 提交规范

- 单条改动尽量**关注点分离**（一个逻辑一件事），方便 review 与回滚。
- 提交信息建议遵循 `type(scope): 简述`：
  - `feat` 新功能 / `fix` 缺陷 / `refactor` 重构 / `test` 测试 / `docs` 文档 / `chore` 构建与杂项
  - 例：`fix(recommend): 修正与 tool-registry 的 8088 端口冲突`
- 提交前本地自测：

```bash
export JAVA_HOME="D:/Program Files/Java/jdk-21.0.6+7"   # 示例，按本机实际路径
mvn -pl <module> -am test                                # 跑目标模块及依赖的测试
```

## 4. CI 卡点（required status checks）

PR 合并前必须通过以下两个 GitHub Actions job（见 `.github/workflows/eval-gate.yml`）：

| Check | 校验内容 |
|-------|----------|
| `eval-gate` | 路由 E2E 回归（`RouterServiceEndToEndTest`）与约定的集成校验 |
| `quality-gate` | Maven Enforcer 构建卫生（Java/Maven 版本、依赖版本去重）+ 模块单测与覆盖率 |

本地若想提前发现 Enforcer 问题，执行：

```bash
mvn validate        # 触发 enforcer:enforce（requireJavaVersion / requireMavenVersion / banDuplicatePomDependencyVersions）
```

## 5. 代码风格

- Java：Spring Boot 约定，Lombok 简化样板；异常处理统一走 `common` 的 `GlobalExceptionHandler` + `ExceptionHandlerSupport`。
- 跨模块重复的类/配置优先下沉到 `smart-assistant-common`，避免逻辑漂移。
- 新增 REST 端点请在 `docs/api-reference.md` 同步登记。
- 中文注释与日志为主，关键公共 API 提供简洁说明。

## 6. 测试要求

- 纯逻辑/工具类/配置类优先补**单元测试**（Mockito + JUnit5，已在 `spring-boot-starter-test` 中）。
- 避免引入 TestContainers 等重型依赖；薄弱模块（embedding/user/recommend/general）已有针对性单测，新增功能请补齐对应用例。
- 运行单测指定类名可避免触发需外部组件的集成测试：

```bash
mvn test -Dtest=EmbeddingControllerTest,RecommendServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 7. 文档同步

以下文档需与代码保持同步，改动相关部分时请一并更新：

- `docs/api-reference.md` — 端点与端口
- `docs/local-setup.md` — 本地搭建步骤
- `README.md` / `ai-project-context.md` — 架构与服务清单
