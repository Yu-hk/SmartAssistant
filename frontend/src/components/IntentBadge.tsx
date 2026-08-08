import React from 'react';
import { IntentType, INTENT_LABELS, INTENT_COLORS, normalizeIntentType } from '../types';

interface IntentBadgeProps {
  intent: IntentType;
  size?: 'sm' | 'md';
}

export function IntentBadge({ intent, size = 'md' }: IntentBadgeProps) {
  const normalizedIntent = normalizeIntentType(intent);
  if (normalizedIntent === 'unknown') return null;

  const color = INTENT_COLORS[normalizedIntent];
  const label = INTENT_LABELS[normalizedIntent];

  const iconMap: Record<IntentType, string> = {
    refund: '💰',
    order: '📦',
    product: '🛍️',
    tech: '🔧',
    general: '💬',
    unknown: '❓',
  };

  const padding = size === 'sm' ? '3px 10px' : '4px 14px';
  const fontSize = size === 'sm' ? '11px' : '12px';

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '5px',
        padding,
        borderRadius: '100px',
        backgroundColor: color + '15',
        color,
        fontSize,
        fontWeight: 500,
        border: `1px solid ${color}30`,
        lineHeight: 1.4,
        whiteSpace: 'nowrap',
        boxShadow: `0 0 12px ${color}20`,
      }}
    >
      <span style={{ fontSize: size === 'sm' ? '10px' : '12px' }}>{iconMap[normalizedIntent]}</span>
      {label}
    </span>
  );
}
