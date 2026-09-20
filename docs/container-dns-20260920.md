# 应用容器 DNS 隔离（2026-09-20）

## 根因与对照实验

现场为 Podman 4.9.4-rhel + CNI 的 `smart-network`，名称解析由 dnsname 插件的 dnsmasq 提供，不是 netavark/aardvark。应用镜像使用 musl；原容器同时继承网络网关与两个宿主机上游 DNS。

musl 对多个 nameserver 并行查询，并非按列表顺序逐个故障转移。[musl 官方说明](https://wiki.musl-libc.org/functional-differences-from-glibc.html)。直接 UDP 查询确认：网络 DNS 对内部服务名返回成功，上游 DNS 对相同服务名返回 NXDOMAIN；上游负回答先返回时，可能导致 Java `UnknownHostException`。

- 同一生产 JVM 镜像、关闭探针进程自身的正/负 DNS 缓存，进行 600 次查询：4 个内部名称、1 个外部名称、1 个应不存在名称各 100 次。
- 继承原三个 DNS：3 次失败（向量服务 2 次、Redis 1 次）。
- 仅使用经网络 inspect 校验的 CNI 网关 DNS：600 次全部符合预期，包括外部域名转发与不存在名称的负回答。
- 读取探针容器实际 `/etc/resolv.conf`，不是只检查启动参数。内部名称需匹配当时 inspect 获得的服务地址；测试前后核验目标容器 ID、地址和运行状态未变化。

证据目录：`/opt/smart-assistant/releases/container-dns-20260920`，保留 `inherited.json`、`internal.json` 及失败发布日志。探针不挂载数据库，不注入生产凭据，不调用业务写接口，结束后按唯一标签清除临时容器及临时依赖。

## 变更范围

使用 `deploy/podman-cni-dns.override.yml` 为 8 个 Java 应用服务显式配置唯一内部 DNS。网关地址从实际网络导出，不硬编码到仓库；此覆盖文件仅适用于已核验的 Podman CNI 环境，不能直接套用到普通 Docker 或其他网络。

```sh
export SMART_INTERNAL_DNS="$(podman network inspect smart-network | python3 scripts/container_dns_policy.py)"
# 在现有完整部署配置上追加 deploy/podman-cni-dns.override.yml，并先检查合成配置。
# 不要只用此文件启动服务；不要跳过现有恢复控制挂载、镜像/JAR 和环境配置。
```

网络 DNS 继续转发外部域名。没有修改宿主机 DNS、自动生成的 dnsmasq 配置、CNI 后端、防火墙或基础设施容器。应用 JAR、镜像、恢复来源 pin 和控制目录读写权限保持不变。现场上一版向量服务固定 IP 的临时环境覆盖同时改为服务名，其他环境值不变。

## 发布保护与已遇到的失败

第一次切换因环境变量数组的顺序不同触发严格比较而中止；旧容器已重新启用。回退时向量服务重新联网获得新地址，而订单服务保留上一版固定地址覆盖，导致订单健康检查超时；恢复原地址后订单健康通过。原失败日志保留，不能把这次维护称为无中断发布。

修正后先创建但不启动候选容器，逐个验证镜像、JAR、环境变量值、挂载权限、资源和健康检查配置，再按标签删除预检容器。环境变量按无序列表比较，日志比较驱动及选项而非容器 ID 派生的日志文件路径。回滚显式恢复原容器网络地址，避免旧配置失效。

第二次切换遇到 Podman/runc 的 cgroup `unable to freeze` 启动错误，再次回退。启动保护改为逐个启动，仅对此尚未运行的容器启动错误作最多三次重试；不重跑业务请求。回滚会继续恢复其余服务及入口，不因单个容器恢复失败而跳过后续服务。没有修改宿主机 cgroup 或内核设置。

停止入口后仅在派发/画像提交队列排空、没有运行中会话时切换；保留全部上一版容器及 JAR。真实业务和检索复测完成前不标记发布通过。

第三次切换中商品容器再次遇到该运行时错误，第二次启动成功；8 个新应用容器最终全部健康。容器内实际 JAR 哈希与切换前一致，实际 DNS 均只包含经校验的内部网关，来源 pin 和恢复控制挂载保持不变。切换后再次运行 600 次解析，零失败，结果留存 `container-dns-final-20260920/internal.json`。

公网独立测试账号完成价格库存、规格追问和退货条件三种问答，均包含工具用量、Token 和 done 事件，规格回答不包含颜色；匿名隐私请求为 401、登录后可用。测试会话关闭为 200，令牌撤销且私有凭据文件已移除。未发起真实订单、退款或画像删除。这里只认证 API，不冒充真实浏览器视觉验收。

最后从运行 Consumer JAR 提取依赖，经服务名连接真实向量服务，17 题 × 3 轮共 51 次检索均首位命中，Hit@1/3/5、Recall@1/3/5、MRR@5 均为 1.0；每轮校验实际 DNS，未使用静态 IP 端点。隔离评测容器已清除。该结果仍仅限公开种子，不代表完整生产语料或模型答案质量。

| 证据 | SHA-256 |
| --- | --- |
| 原 DNS 对照 `container-dns-20260920/inherited.json` | `1a36f2fa22dfeac7809d2680b1360673c13c3d9c10a2ad10c86314b7ffea866a` |
| 切换后解析 `container-dns-final-20260920/internal.json` | `53038c9e70182fe29b31daed4e005677f7280af2d853ed5ff918213bea5cf678` |
| 服务名检索 `real-rag-eval-dns-20260920/report.json` | `20dd937b41b486e4e57d5092faa1660cfbee02dd6854c89084d0f9d86def331a` |

上述路径均相对服务器 `/opt/smart-assistant/releases/`。最终八服务及公网健康为 UP；没有新增数据库备份，现有最近一次备份和历史日志未删除。

## 测试与边界

本地 9 项 DNS 配置安全测试、5 项真实 JVM 本机解析测试通过，CI 同步执行。真实 RAG 探针新增 `--endpoint-mode internal-dns`，使用服务名并验证容器实际 DNS；失败不会自动转回固定 IP 并算作 DNS 验证通过。

600 次对照实验不是长期零失败保证，也不覆盖 DNS 网关宕机、多副本、网络分区或整机恢复。nginx 与基础设施容器不在本次 DNS 重建范围。浏览器视觉验收与完整生产语料/独立留出集模型效果评估仍是独立未完成事项。
