import assert from 'node:assert/strict';
import { extractOrderChoices } from '../frontend/src/utils/orderChoices.ts';

const response = `查到您最近的3笔订单：

1. ORD-LOAD000001001 | 食品 并发测试款 0048 | 待付款 | 2026-08-01 09:43
2. **ORD-LOAD000001002** ｜ 手机 并发测试款 0065 ｜ 待发货 ｜ 2026-08-01 09:42
3、ORD-LOAD000001003 | 平板电脑 并发测试款 0082 | 已发货 | 2026-08-01 09:41

请选择需要查看的订单。`;

const choices = extractOrderChoices(response);
assert.equal(choices.length, 3);
assert.deepEqual(choices[0], {
  orderId: 'ORD-LOAD000001001',
  title: '食品 并发测试款 0048',
  details: ['待付款', '2026-08-01 09:43'],
});
assert.equal(choices[1].orderId, 'ORD-LOAD000001002');
assert.equal(choices[2].title, '平板电脑 并发测试款 0082');

assert.deepEqual(
  extractOrderChoices('订单 ORD-LOAD000001001 的物流状态为运输中。'),
  [],
  'a single detail response must not render another picker',
);

console.log('PASS order choice parser: three selectable cards and no single-order false positive');
