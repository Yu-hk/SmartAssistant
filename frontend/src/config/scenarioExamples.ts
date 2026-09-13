// Shared complete, read-only example questions for every homepage entry.
// Do not include another user's order ID or fabricate catalog availability here.
export const SCENARIO_EXAMPLES = [
  {
    id: 'order', title: '订单助手', description: '查订单、跟物流、处理售后',
    tone: 'cyan', shortLabel: '查看我的订单',
    question: '请查询我当前账号的订单列表。',
  },
  {
    id: 'product', title: '商品顾问', description: '商品咨询、参数对比与推荐',
    tone: 'amber', shortLabel: '耳机价格与库存',
    question: 'AirPods Pro多少钱？有货吗？',
  },
  {
    id: 'knowledge', title: '知识检索', description: '检索资料、文档问答与总结',
    tone: 'emerald', shortLabel: '库存规则查询',
    question: '可售库存如何计算？锁定库存能当作可售库存吗？请根据知识库回答。',
  },
] as const;
