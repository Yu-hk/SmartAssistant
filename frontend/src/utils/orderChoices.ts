export interface OrderChoice {
  orderId: string;
  title: string;
  details: string[];
}

const ORDER_ID = /\bORD-[A-Z0-9][A-Z0-9_-]{2,63}\b/i;
const LIST_PREFIX = /^\s*(?:(?:\d+)[.)、]\s*|[-*]\s+)/;

/**
 * Converts a multi-order text response into selectable order cards.
 * A single order mention is intentionally ignored because detail/logistics
 * responses often repeat one order number and should not show another picker.
 */
export function extractOrderChoices(content: string): OrderChoice[] {
  const choices = new Map<string, OrderChoice>();

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.replace(/[*_`]/g, '').trim();
    const match = line.match(ORDER_ID);
    if (!match) continue;

    const orderId = match[0].toUpperCase();
    if (choices.has(orderId)) continue;

    const normalized = line.replace(LIST_PREFIX, '').trim();
    const parts = normalized
      .split(/\s*[|｜]\s*/)
      .map(part => part.trim())
      .filter(Boolean);
    const idPartIndex = parts.findIndex(part => ORDER_ID.test(part));
    const details = (idPartIndex >= 0 ? parts.slice(idPartIndex + 1) : [])
      .filter(part => !ORDER_ID.test(part))
      .slice(0, 3);

    choices.set(orderId, {
      orderId,
      title: details[0] || `订单 ${orderId}`,
      details: details.slice(1),
    });
  }

  const result = [...choices.values()];
  return result.length >= 2 ? result.slice(0, 5) : [];
}
