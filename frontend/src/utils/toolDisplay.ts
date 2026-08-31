const CAPABILITY_LABELS: Record<string, string> = {
  searchweb: '联网搜索',
  websearch: '联网搜索',
  webfetch: '网页读取',
  gethotnews: '热点资讯',
  calculate: '数学计算',
  executescript: '复杂计算',
  converttemperature: '单位换算',
  convertlength: '单位换算',
  convertweight: '单位换算',
  convertcurrency: '汇率换算',
  analyzeimage: '图片分析',
  generateimage: '图片生成',
  imagegen: '图片生成',
  discover_tools: '能力发现',
  queryorder: '订单查询',
  createorder: '创建订单',
  cancelorder: '取消订单',
  applyrefund: '退款申请',
  refundorder: '退款申请',
  payorder: '订单支付',
  queryproductinfo: '商品查询',
  queryproduct: '商品查询',
  checkstock: '库存查询',
  getprice: '价格查询',
  queryprice: '价格查询',
  savepreference: '偏好记录',
  recallmemories: '历史偏好查询',
  bash: '命令执行',
  write: '文件写入',
  edit: '文件编辑',
  editfile: '文件编辑',
  read: '文件读取',
  readfile: '文件读取',
  listdir: '目录浏览',
  search: '内容搜索',
  grep: '内容搜索',
  delete: '文件删除',
  deletefile: '文件删除',
  skill: '专业技能',
  task: '任务处理',
};

export function getToolCapabilityLabel(toolName?: string): string {
  if (!toolName) return '智能能力';
  const normalized = toolName.toLowerCase().replace(/[.\-]/g, '_');
  const compact = normalized.replace(/_/g, '');
  return CAPABILITY_LABELS[normalized]
    || CAPABILITY_LABELS[compact]
    || inferCapability(normalized);
}

function inferCapability(name: string): string {
  if (name.includes('search') || name.includes('web')) return '联网搜索';
  if (name.includes('order')) return '订单服务';
  if (name.includes('product') || name.includes('stock') || name.includes('price')) return '商品服务';
  if (name.includes('image')) return '图片处理';
  if (name.includes('memory') || name.includes('preference')) return '偏好管理';
  if (name.includes('file')) return '文件处理';
  return '智能能力';
}
