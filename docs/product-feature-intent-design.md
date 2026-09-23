# 商品特性意图设计

## 目标

商品描述分为两类：

- 定性偏好：如“便携”“轻便”“商务”，用于检索和排序，不自动转换为数值问题。
- 定量约束：如重量上限、预算上限、最低续航时长，只有表达明确且可验证时才用于硬筛选。

## 分层

1. `product-feature-schema.properties`：定义字段类型、同义词、单位、场景词、澄清文案和表单字段。
2. `ProductFeatureIntentParser`：理解自然语言并输出 `ProductFeatureIntent`。默认实现是确定性的规则解析器，未来可替换为模型结构化输出。
3. `ProductFeatureIntentValidator`：检查缺失值、冲突、非法范围和不可比较场景，不负责自然语言理解。
4. `ProductFeatureRequestResolver`：组合解析器与校验器，生成现有调用方使用的 `ProductFeatureRequest`。
5. `ProductDiscoveryIntentParser`：组合偏好、特征、预算和商品发现模式；预算采用封闭语法并对多值/范围冲突进行澄清。
6. Common 的 `product-feature-domain.properties`：商品录入和查询共用重量、续航上限与精度边界。

## 安全边界

- 模型或解析器只能提出结构化意图，不能直接绕过校验生成数据库条件。
- 定量约束必须通过确定性校验后才能进入 `ProductFeatureConstraints`。
- 目录缺少结构化事实时，不把未知字段当作满足硬约束。
- 新增定性同义词或修改澄清文案优先改领域配置；新增一种真正的结构化硬约束时，再扩展强类型约束契约与校验器。

## “便携”的行为

“便携/轻便”解析为 `portability` 定性偏好，原始查询继续传给商品检索层。只有用户明确提出重量或重量单位时，才进入重量约束与重量澄清流程。
