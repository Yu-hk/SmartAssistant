/** Only allow public messages, never exception class names, paths or provider errors. */
const PUBLIC_ERRORS: Record<string, string> = {
  CHECKPOINT_NOT_FOUND: '恢复检查点不存在或已经过期。',
  FORBIDDEN: '没有权限恢复这次回答。',
  APPROVAL_REQUIRED: '该任务正在等待确认，请先完成确认。',
  ACTIVE_EXECUTION: '任务仍在执行，请稍后再试。',
  CHECKPOINT_VERSION_CONFLICT: '任务状态已经更新，请刷新后重试。',
};
export function recoveryErrorMessage(error: unknown): string {
  const candidate = error as { status?: number; body?: string } | null;
  if ([404, 503].includes(candidate?.status || 0)) return '回答恢复功能暂不可用，请稍后再试。';
  if (candidate?.status === 401) return '登录已失效，请重新登录。';
  if (candidate?.status === 403) return PUBLIC_ERRORS.FORBIDDEN;
  try {
    const body = JSON.parse(candidate?.body || '{}');
    const code = body.error?.code || body.code;
    return PUBLIC_ERRORS[code] || '恢复暂未完成，请稍后重试或联系管理员核查。';
  } catch {
    return '恢复暂未完成，请稍后重试或联系管理员核查。';
  }
}

export function publicRecoveryError(message: string): string {
  const allowed = [...Object.values(PUBLIC_ERRORS), '回答恢复功能暂不可用，请稍后再试。',
    '登录已失效，请重新登录。', '恢复暂未完成，请稍后重试或联系管理员核查。'];
  return allowed.includes(message) ? message : '恢复暂未完成，请稍后重试或联系管理员核查。';
}
