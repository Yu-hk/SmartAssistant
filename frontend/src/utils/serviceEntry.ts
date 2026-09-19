export const SERVICE_NAMES = ['售前顾问', '技术支持', '订单助手', '投诉处理', '知识管家'] as const;

/** Service shortcuts prepare a draft, never create a session or overwrite custom text. */
export function serviceEntryDraft(current: string, service: string): string {
  if (!SERVICE_NAMES.some(name => name === service)) return current;
  const isTemplate = SERVICE_NAMES.some(name => current.trim() === `我需要${name}：`);
  return !current.trim() || isTemplate ? `我需要${service}：` : current;
}
