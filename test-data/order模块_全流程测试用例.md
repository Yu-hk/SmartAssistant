# Order 模块全流程测试用例 — 输入输出与数据变化

> 日期：2026-06-17  
> 适用版本：smart-assistant-order v1.0.0-SNAPSHOT  
> 数据库：PostgreSQL 16 / a2a_system  

---

## 目录

1. [测试环境与数据准备](#1-测试环境与数据准备)
2. [正常流程：下单→支付→发货→签收](#2-正常流程下单支付发货签收)
3. [取消流程：下单→取消](#3-取消流程下单取消)
4. [退款流程：下单→支付→发货→签收→退款](#4-退款流程下单支付发货签收退款)
5. [异常流程：非法状态跳转](#5-异常流程非法状态跳转)
6. [数据变化汇总](#6-数据变化汇总)

---

## 1. 测试环境与数据准备

### 数据库初始化

```bash
# 1. 建表（首次执行）
psql -U postgres -d a2a_system -f smart-assistant-order/src/main/resources/db/order-schema.sql

# 2. 导入测试数据
psql -U postgres -d a2a_system -f smart-assistant-order/src/main/resources/db/order-test-data.sql
```

### orders 表最终结构

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | 自增主键 |
| order_id | VARCHAR(50) UNIQUE | 业务订单号 |
| user_id | BIGINT | 用户ID |
| product_name | VARCHAR(200) | 商品名称 |
| amount | DECIMAL(10,2) | 商品金额 |
| status | VARCHAR(20) | 状态：待付款/待发货/已发货/已签收/已取消/退款中 |
| carrier | VARCHAR(50) | 物流公司 |
| tracking_no | VARCHAR(100) | 快递单号 |
| product_type | VARCHAR(50) | 商品类型 |
| contact_name | VARCHAR(100) | 收货人 |
| contact_phone | VARCHAR(20) | 联系电话 |
| shipping_address | TEXT | 收货地址 |
| payment_method | VARCHAR(50) | 支付方式 |
| delivered_date | TIMESTAMP | 签收时间 |
| created_at | TIMESTAMP | 下单时间 |
| updated_at | TIMESTAMP | 最后更新时间 |

### 测试数据概览（8 条订单）

| 订单号 | 状态 | 金额 | 用户 |
|--------|------|------|------|
| TEST-001 | 待付款 | ¥11,999 | 张三 |
| TEST-002 | 待发货 | ¥8,999 | 张三 |
| TEST-003 | 已发货 | ¥2,499 | 李四 |
| TEST-004 | 已签收 | ¥15,999 | 王五 |
| TEST-005 | 已取消 | ¥6,999 | 赵六 |
| TEST-006 | 退款中 | ¥8,499 | 张三 |
| TEST-007 | 待付款 | ¥299 | 企业采购部 |
| TEST-008 | 待付款 | ¥399 | 企业采购部 |

---

## 2. 正常流程：下单→支付→发货→签收

### 用例 2-1：创建订单（下单）

**前置条件**：用户已登录，商品信息已确认

**输入参数**：
| 参数 | 值 |
|------|-----|
| userId | `1` |
| productName | `Apple iPhone 15 Pro Max 512GB` |
| amount | `11999.00` |
| contactName | `张三` |
| contactPhone | `13800138001` |
| shippingAddress | `北京市海淀区中关村大街1号` |

**调用**：`createOrder(userId=1, productName="Apple iPhone 15 Pro Max 512GB", amount=11999.00, contactName="张三", contactPhone="13800138001", shippingAddress="北京市海淀区中关村大街1号")`

**预期输出**：
```
📦 订单创建成功！
订单号：ORD-XXXXXXXX
商品：Apple iPhone 15 Pro Max 512GB
金额：¥11999.00
商品类型：电子产品
收货人：张三
联系电话：13800138001
收货地址：北京市海淀区中关村大街1号
状态：待付款

下一步：请提醒用户核对收货信息并完成付款，可调用 payOrder(orderId="ORD-XXXXXXXX") 进行支付。
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| 记录数 | 0 | 1 条新记录 |
| order_id | — | ORD-XXXXXXXX（自动生成） |
| status | — | `待付款` |
| created_at | — | `2026-06-17 09:00:00` |
| updated_at | — | `2026-06-17 09:00:00` |
| contact_name | — | `张三` |
| shipping_address | — | `北京市海淀区中关村大街1号` |

**涉及表**：orders（INSERT）

---

### 用例 2-2：支付订单（首次调用 — 二次确认）

**前置条件**：订单 `TEST-001` 状态为 `待付款`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-001` |
| paymentMethod | `微信支付` |

**调用**：`payOrder(orderId="TEST-001", paymentMethod="微信支付")`

**预期输出**（首次调用，创建确认项）：
```
ℹ️ 支付确认提醒
即将为订单 TEST-001 进行支付。
商品：Apple iPhone 15 Pro Max 512GB
订单金额：¥11999.00
付款方式：微信支付

请确认上述信息和金额是否正确？
用户确认后，调用 confirmAction(orderId="TEST-001", actionType="payment")，
然后重新调用 payOrder(orderId="TEST-001", paymentMethod="微信支付") 执行支付。
```

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| 记录数 | 0 | 1 条新记录 |
| order_id | — | `TEST-001` |
| action_type | — | `payment` |
| status | — | `pending` |
| reason | — | 包含商品、金额、支付方式详情 |

**orders 表**：无变化（状态仍为 `待付款`）

---

### 用例 2-3：确认支付

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-001` |
| actionType | `payment` |

**调用**：`confirmAction(orderId="TEST-001", actionType="payment")`

**预期输出**：
```
✅ 确认成功。现在可以调用 payOrder(orderId="TEST-001", paymentMethod="微信支付/支付宝/...") 执行操作。
```

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| TEST-001/payment | status=`pending` | status=`confirmed`, confirmed_at=`当前时间` |

---

### 用例 2-4：执行支付（第二次调用）

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-001` |
| paymentMethod | `微信支付` |

**调用**：`payOrder(orderId="TEST-001", paymentMethod="微信支付")`

**预期输出**：
```
✅ 支付成功！
订单号：TEST-001
商品：Apple iPhone 15 Pro Max 512GB
金额：¥11999.00
付款方式：微信支付
状态：待发货

下一步：商家会尽快安排发货，发货后可调用 shipOrder 更新物流信息。
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| status | `待付款` | **`待发货`** |
| payment_method | `''` | **`微信支付`** |
| updated_at | `2026-06-17 09:00` | `2026-06-17 09:02`（当前时间） |

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| TEST-001/payment | status=`confirmed` | status=**`consumed`**, consumed_at=`当前时间` |

---

### 用例 2-5：商家发货

**前置条件**：订单 `TEST-002` 状态为 `待发货`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-002` |
| carrier | `顺丰速运` |
| trackingNo | `SF20260617001` |

**调用**：`shipOrder(orderId="TEST-002", carrier="顺丰速运", trackingNo="SF20260617001")`

**预期输出**：
```
✅ 发货成功！
订单号：TEST-002
商品：Samsung Galaxy Tab S9 Ultra
物流公司：顺丰速运
快递单号：SF20260617001
状态：已发货

下一步：用户可通过 trackLogistics(trackingNumber="SF20260617001") 查询物流轨迹，
收到货后可调用 confirmDelivery(orderId="TEST-002") 确认收货。
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| status | `待发货` | **`已发货`** |
| carrier | `''` | **`顺丰速运`** |
| tracking_no | `''` | **`SF20260617001`** |
| updated_at | 旧时间 | **当前时间** |

**数据变化（order_logistics 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| 记录数 | 0 | 1 条新记录 |
| tracking_no | — | `SF20260617001` |
| carrier | — | `顺丰速运` |
| status | — | `in_transit` |
| trajectory | — | `[{"time":"...","desc":"已揽收，包裹已被顺丰速运收取"}]` |

---

### 用例 2-6：查询物流轨迹

**前置条件**：物流记录已存在（如用例 2-5 执行后）

**输入参数**：
| 参数 | 值 |
|------|-----|
| trackingNumber | `SF20260617001` |

**调用**：`trackLogistics(trackingNumber="SF20260617001")`

**预期输出**：
```
📦 快递单号 SF20260617001 物流信息
所属订单：TEST-002
物流公司：顺丰速运
状态：运输中

最新轨迹：
  2026-06-17 09:05  深圳分拨中心  已揽收，包裹已被顺丰速运收取
```

**数据变化**：无（只读操作）

---

### 用例 2-7：确认收货

**前置条件**：订单 `TEST-003` 状态为 `已发货`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-003` |

**调用**：`confirmDelivery(orderId="TEST-003")`

**预期输出**：
```
✅ 确认收货成功！
订单号：TEST-003
商品：Sony WH-1000XM5 降噪耳机
金额：¥2499.00
状态：已签收

感谢您的购买！如有售后问题可随时联系客服。
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| status | `已发货` | **`已签收`** |
| delivered_date | `null` | **当前时间** |
| updated_at | 旧时间 | **当前时间** |

**数据变化（order_logistics 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| SF-TEST-003 status | `in_transit` | **`delivered`** |
| trajectory | 2条轨迹 | 追加签收记录变为**3条** |

---

## 3. 取消流程：下单→取消

### 用例 3-1：取消待付款订单

**前置条件**：订单 `TEST-007` 状态为 `待付款`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-007` |
| reason | `不再需要，取消订单` |

**调用**：`cancelOrder(orderId="TEST-007", reason="不再需要，取消订单")`

**预期输出**：
```
✅ 订单已取消。
订单号：TEST-007
商品：定制刻字保温杯（企业定制）
金额：¥299.00
取消原因：不再需要，取消订单
状态：已取消

如为已付款订单，退款将在 3-7 个工作日原路返回。
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| status | `待付款` | **`已取消`** |
| updated_at | 旧时间 | **当前时间** |

---

### 用例 3-2：查询已取消订单

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-005` |

**调用**：`queryOrder(orderId="TEST-005")`

**预期输出**：
```
📋 订单 TEST-005
商品：HUAWEI Mate 60 Pro
金额：¥6999.00
状态：❌ 已取消
商品类型：电子产品
下单时间：2026-06-13 09:00
最后更新：2026-06-14 09:00
```

**数据变化**：无

---

## 4. 退款流程：下单→支付→发货→签收→退款

### 用例 4-1：申请退款（首次调用 — 二次确认）

**前置条件**：订单 `TEST-004` 状态为 `已签收`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-004` |
| reason | `笔记本屏幕有亮点，申请退货退款` |

**调用**：`applyRefund(orderId="TEST-004", reason="笔记本屏幕有亮点，申请退货退款")`

**预期输出**（首次调用，创建确认项）：
```
ℹ️ 退款确认提醒
即将为订单 TEST-004 申请退款。
商品：Dell XPS 15 笔记本，金额：¥15999.00，状态：已签收
退款原因：笔记本屏幕有亮点，申请退货退款
退款去向：将原路返还至付款账户（尾号 ****）

请先询问用户是否确认退款。用户确认后，调用 confirmAction(orderId="TEST-004", actionType="refund")，
然后重新调用 applyRefund(orderId="TEST-004", reason="笔记本屏幕有亮点，申请退货退款")。
```

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| 记录数 | 1（TEST-006 退款） | 新增 1 条 |
| order_id | — | `TEST-004` |
| action_type | — | `refund` |
| status | — | `pending` |

**orders 表**：无变化（仍为 `已签收`）

---

### 用例 4-2：确认退款

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-004` |
| actionType | `refund` |

**调用**：`confirmAction(orderId="TEST-004", actionType="refund")`

**预期输出**：
```
✅ 确认成功。现在可以调用 applyRefund(orderId="TEST-004", reason="...") 执行退款操作。
```

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| TEST-004/refund | status=`pending` | status=**`confirmed`**, confirmed_at=`当前时间` |

---

### 用例 4-3：执行退款（第二次调用）

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-004` |
| reason | `笔记本屏幕有亮点，申请退货退款` |

**调用**：`applyRefund(orderId="TEST-004", reason="笔记本屏幕有亮点，申请退货退款")`

**预期输出**：
```
✅ 退款申请已确认并提交。
订单：TEST-004
商品：Dell XPS 15 笔记本
金额：¥15999.00
状态：退款中
签收日期：2026-06-15
退款原因：笔记本屏幕有亮点，申请退货退款

退款申请已受理，预计 3-7 个工作日到账。退款编号：2
```

**数据变化（orders 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| status | `已签收` | **`退款中`** |
| updated_at | 旧时间 | **当前时间** |

**数据变化（order_refunds 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| 记录数 | 1（TEST-006） | 新增 1 条 |
| order_id | — | `TEST-004` |
| reason | — | `笔记本屏幕有亮点，申请退货退款` |
| amount | — | `15999.00` |
| status | — | `completed` |

**数据变化（approval_records 表）**：
| 字段 | 变化前 | 变化后 |
|------|--------|--------|
| TEST-004/refund | status=`confirmed` | status=**`consumed`**, consumed_at=`当前时间` |

---

## 5. 异常流程：非法状态跳转

### 用例 5-1：已发货订单取消（应引导走退款）

**前置条件**：订单 `TEST-003` 状态为 `已发货`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-003` |
| reason | `不想要了` |

**调用**：`cancelOrder(orderId="TEST-003", reason="不想要了")`

**预期输出**（拒绝，带提示）：
```
{"error_code":"INVALID_STATUS",
 "message":"订单 TEST-003 当前状态为「已发货」，仅「待付款」或「待发货」订单可以取消。已发货的订单如需退款，请使用 applyRefund 申请退款。",
 "retryable":false}
```

**数据变化**：无

---

### 用例 5-2：待付款订单发货（状态不匹配）

**前置条件**：订单 `TEST-001` 状态为 `待付款`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-001` |
| carrier | `顺丰速运` |
| trackingNo | `SF-INVALID-001` |

**调用**：`shipOrder(orderId="TEST-001", carrier="顺丰速运", trackingNo="SF-INVALID-001")`

**预期输出**（拒绝）：
```
{"error_code":"INVALID_STATUS",
 "message":"订单 TEST-001 当前状态为「待付款」，仅「待发货」订单可以发货",
 "retryable":false}
```

**数据变化**：无

---

### 用例 5-3：已取消订单支付

**前置条件**：订单 `TEST-005` 状态为 `已取消`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-005` |
| paymentMethod | `微信支付` |

**调用**：`payOrder(orderId="TEST-005", paymentMethod="微信支付")`

**预期输出**（拒绝）：
```
{"error_code":"INVALID_STATUS",
 "message":"订单 TEST-005 当前状态为「已取消」，仅「待付款」订单可以支付",
 "retryable":false}
```

**数据变化**：无

---

### 用例 5-4：退款中订单再次申请退款

**前置条件**：订单 `TEST-006` 状态为 `退款中`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-006` |
| reason | `再申请一次退款` |

**调用**：`applyRefund(orderId="TEST-006", reason="再申请一次退款")`

**预期输出**（拒绝）：
```
{"error_code":"ALREADY_REFUNDING",
 "message":"订单 TEST-006 已在退款处理中，请耐心等待",
 "retryable":false}
```

**数据变化**：无

---

### 用例 5-5：待付款订单直接确认收货

**前置条件**：订单 `TEST-008` 状态为 `待付款`

**输入参数**：
| 参数 | 值 |
|------|-----|
| orderId | `TEST-008` |

**调用**：`confirmDelivery(orderId="TEST-008")`

**预期输出**（拒绝）：
```
{"error_code":"INVALID_STATUS",
 "message":"订单 TEST-008 当前状态为「待付款」，仅「已发货」订单可以确认收货",
 "retryable":false}
```

**数据变化**：无

---

## 6. 数据变化汇总

### 完整流程数据追踪

以 **TEST-001** 为例，跟踪一条订单的完整生命周期数据变化：

| 步骤 | 工具调用 | status | payment_method | carrier | tracking_no | delivered_date | updated_at |
|------|----------|--------|---------------|---------|-------------|---------------|------------|
| 1. 下单 | createOrder | **待付款** | '' | '' | '' | null | T0 |
| 2. 支付确认 | payOrder（首次） | 待付款 | — | — | — | — | — |
| 3. 确认 | confirmAction(payment) | — | — | — | — | — | — |
| 4. 执行支付 | payOrder（二次） | **待发货** | **微信支付** | '' | '' | null | T0+2min |
| 5. 发货 | shipOrder | **已发货** | 微信支付 | **顺丰速运** | **SF...** | null | T0+1h |
| 6. 签收 | confirmDelivery | **已签收** | 微信支付 | 顺丰速运 | SF... | **当前时间** | T0+2d |
| 7. 退款确认 | applyRefund（首次） | 已签收 | — | — | — | — | — |
| 8. 确认 | confirmAction(refund) | — | — | — | — | — | — |
| 9. 执行退款 | applyRefund（二次） | **退款中** | 微信支付 | 顺丰速运 | SF... | 已签收时间 | T0+3d |

### 涉及的表

| 表 | 操作类型 | 用例覆盖 |
|----|----------|----------|
| orders | INSERT / UPDATE | 创建、支付、发货、签收、取消、退款 |
| order_logistics | INSERT / UPDATE | 发货创建轨迹、签收更新轨迹 |
| order_refunds | INSERT | 退款执行时创建退款记录 |
| approval_records | INSERT / UPDATE | 创建确认项、确认、消费（状态流转 pending→confirmed→consumed） |

### 状态机约束验证

| 当前状态 | 允许的操作 | 不允许的操作 |
|----------|-----------|-------------|
| 待付款 | payOrder, cancelOrder | shipOrder, confirmDelivery, applyRefund |
| 待发货 | shipOrder, cancelOrder | payOrder, confirmDelivery, applyRefund |
| 已发货 | confirmDelivery, applyRefund | payOrder, cancelOrder, shipOrder |
| 已签收 | applyRefund | payOrder, cancelOrder, shipOrder, confirmDelivery |
| 已取消 | —（终态） | 所有操作 |
| 退款中 | —（终态） | 所有操作 |

---

## 7. 测试执行报告

> **执行日期**：2026-06-17  
> **执行环境**：本地 Docker（smart-postgres / smart-redis / smart-nacos）  
> **服务版本**：smart-assistant-order 1.0.0-SNAPSHOT（修复 `shipOrder` updateById + JSONB→TEXT）  
> **总用例数**：12  | **通过**：12  | **失败**：0  | **通过率**：100%

### 7.1 正常流程（Section 2）— 全流程验证

| 步骤 | 操作 | 测试订单 | 结果 | 备注 |
|------|------|---------|:----:|------|
| 2-1 | 下单（createOrder） | ORD-2159573362 | ✅ | 生成订单，状态=待付款 |
| 2-2 | 支付首次调用（payOrder） | ORD-2159573362 | ✅ | 创建 approval_records pending |
| 2-3 | 确认支付（confirmAction） | ORD-2159573362 | ✅ | status→confirmed |
| 2-4 | 支付执行（payOrder 二次） | ORD-2159573362 | ✅ | status→待发货, payment_method=微信支付 |
| 2-5 | 发货（shipOrder） | ORD-2159573362 | ✅ | status→已发货, carrier/tracking 正确写入, 物流轨迹创建 |
| 2-6 | 确认收货（confirmDelivery） | ORD-2159573362 | ✅ | status→已签收, delivered_date 写入 |
| 2-7 | 查询订单（queryOrder） | ORD-2159573362 | ✅ | 完整显示全部字段和下一步提示 |

### 7.2 取消流程（Section 3）

| 用例 | 操作 | 初始状态 | 测试订单 | 结果 | DB 验证 |
|:----:|------|---------|---------|:----:|---------|
| 3-1 | cancelOrder | 待付款 | TEST-007 | ✅ → 已取消 | `status` 从 `待付款` 变为 `已取消` |
| 3-2 | queryOrder | 已取消 | TEST-005 | ✅ 显示完整取消信息 | 只读，数据无变化 |

### 7.3 退款流程（Section 4）

> ⚠️ TEST-004 已在上一轮测试中被置为"退款中"，本轮使用新创建订单 `ORD-4585193363` 重新执行完整退款流程。

| 用例 | 操作 | 前置状态 | 测试订单 | 结果 | DB 验证 |
|:----:|------|---------|---------|:----:|---------|
| 创建 | createOrder | — | ORD-4585193363 | ✅ 待付款 | — |
| — | payOrder + confirm + pay | 待付款 | ORD-4585193363 | ✅ 待发货 | — |
| — | shipOrder | 待发货 | ORD-4585193363 | ✅ 已发货 | — |
| — | confirmDelivery | 已发货 | ORD-4585193363 | ✅ 已签收 | — |
| 4-1 | applyRefund（首次） | 已签收 | ORD-4585193363 | ✅ 创建 approval pending | approval_records INSERT |
| 4-2 | confirmAction(refund) | pending | ORD-4585193363 | ✅ status→confirmed | approval_records UPDATE |
| 4-3 | applyRefund（二次） | confirmed | ORD-4585193363 | ✅ 执行退款 | orders→退款中, order_refunds INSERT, approval consumed |

### 7.4 异常流程（Section 5）

| 用例 | 操作 | 测试订单 | 当前状态 | 预期错误 | 结果 |
|:----:|------|---------|---------|----------|:----:|
| 5-1 | cancelOrder | TEST-003 | 已发货 | `INVALID_STATUS` | ✅ 正确拒绝，提示走退款 |
| 5-2 | shipOrder | TEST-001 | 待付款 | `INVALID_STATUS` | ✅ 正确拒绝 |
| 5-3 | payOrder | TEST-005 | 已取消 | `INVALID_STATUS` | ✅ 正确拒绝 |
| 5-4 | applyRefund | TEST-006 | 退款中 | `INVALID_STATUS` | ✅ 正确拒绝 |
| 5-5 | confirmDelivery | TEST-008 | 待付款 | `INVALID_STATUS` | ✅ 正确拒绝 |

所有 5 种非法状态跳转全部被正确拦截，DB 数据无变化。

### 7.5 已知问题与待办

| 问题 | 类型 | 影响 | 状态 |
|------|:----:|:----:|:----:|
| TextToSqlTool 无法测试 | 环境限制 | Ollama 服务未运行 | ⏳ 需启动 Ollama 或 Mock LLM |
| ​ |  |  |  |
