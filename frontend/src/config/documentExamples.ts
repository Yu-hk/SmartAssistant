export const DOCUMENT_EXAMPLES = [
  { id: 'headphones-v1', title: '耳机参数 · 版本一', fileName: 'headphones-v1.txt',
    description: '验证从资料提取具体参数', question: 'A款耳机支持哪个蓝牙版本，续航多久？',
    expected: '蓝牙5.3，续航30小时。', missingQuestion: '这款耳机的防水等级是多少？' },
  { id: 'headphones-v2', title: '耳机参数 · 版本二', fileName: 'headphones-v2.md',
    description: '同一个问题，更换资料后答案应变化', question: 'A款耳机支持哪个蓝牙版本，续航多久？',
    expected: '蓝牙5.0，续航18小时；不应沿用版本一。', missingQuestion: '这款耳机的售价是多少？' },
  { id: 'workshop', title: '活动指南', fileName: 'workshop-guide.md',
    description: '验证多项信息提取与缺失信息处理', question: '活动在哪里举行、几点开始，需要携带什么？',
    expected: '星河图书馆二楼多功能厅，星期六14:00开始，自带笔记本和笔。',
    missingQuestion: '活动提供停车位吗？' },
] as const;

export const documentExampleUrl = (fileName: string) => `/examples/documents/${encodeURIComponent(fileName)}`;
