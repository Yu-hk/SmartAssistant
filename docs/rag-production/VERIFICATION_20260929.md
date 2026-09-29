# 商品 RAG 选域与自适应融合：2026-09-29 验收记录

## 发布范围

- 仅替换生产 `smart-product` 的只读挂载 JAR；未变更数据库、知识库数据或模型凭证。
- 发布 JAR SHA-256：`6221dd176317826882e81d0e74918712ec564c6175a546ad5a18f9e857a07f52`。运行容器内 `/app/app.jar` 校验一致。
- 切换时短暂停止网关与商品服务，发布完成后两者恢复运行；旧容器与其原有只读挂载 JAR 保留，便于回滚。
- 发布脚本：`scripts/release_product_rag_20260929.py`。发布前后快照与 `deployment.json` 保存在服务器 `/opt/smart-assistant/releases/product-rag-scope-weight-20260929/`，不含在仓库内。

## 验证结果

- 本地定向测试：`ProductKnowledgeScopeSelectorTest`、`KnowledgeSearchHandlerScopeTest`、`RrfFusionHandlerTest`、`ProductRagServiceAgenticTest`，共 9 个测试通过。覆盖多知识库时只选商品库，以及稀疏/稠密权重改变候选顺序与无权重回退。Windows 本机 JaCoCo agent 异常，因此测试命令使用 `-Djacoco.skip=true`；未将覆盖率验证计为通过。
- 线上发布前预检 `PREFLIGHT_OK`、队列排空 `DRAINED`、内部检查 `HEALTHY smart-product`、公网检查 `PUBLIC_HEALTHY`，发布完成 `DEPLOYED`。后续公网 `/healthz` 为 `UP`，`smart-gateway` 与 `smart-product` 均为 `running`。
- 首次只读咨询“7天无理由退货政策是什么？”返回无证据；商品日志显示对 `smart-embedding-service` 的瞬时 DNS 解析失败，知识库路径未召回。随后的容器内主机名解析和向量服务健康检查正常；不将首次结果计为通过。
- 原问题重试后返回带 `[CID:ORD-REFUND-001]`、`[CID:ORD-REFUND-003]` 引用的答案。日志：`KnowledgeSearch: 1 results`、`RRF 融合完成: activePaths=1`、`RAG 知识已注入上下文`。
- 另一条只读咨询“AirPods Pro 的退货政策是什么？”也返回带知识库引用的答案。两次成功案例均只有知识库一路命中，故确认了选域后的知识库检索链路，但没有在线上观察到多路候选排序变化。自适应加权排序的差异由本地测试验证。

## 保留观察项

首次请求暴露出服务启动后短时 DNS 不可用的可能性。当前解析及问答已恢复，但需在后续滚动发布中监控启动后的首批检索失败率。若复现，应单独调查 Podman DNS/向量服务发现时序；健康检查为 `UP` 不足以代表 RAG 检索已就绪。

Multi-Query 保持关闭。是否引入需另行建立带标注问题集，比较召回、事实准确率、P95 延迟和每问成本；本轮没有把 Multi-Query 作为已上线功能。
