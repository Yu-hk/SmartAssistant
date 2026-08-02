import React, { useState } from 'react';
import { extractOrderChoices } from '../utils/orderChoices';

interface OrderChoicePanelProps {
  content: string;
  disabled?: boolean;
  onSelect: (orderId: string) => void;
}

export function OrderChoicePanel({ content, disabled, onSelect }: OrderChoicePanelProps) {
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
  const choices = extractOrderChoices(content);
  if (choices.length === 0) return null;

  const handleSelect = (orderId: string) => {
    if (disabled || selectedOrderId) return;
    setSelectedOrderId(orderId);
    onSelect(orderId);
  };

  return (
    <div className="order-choice-panel animate-fade-in-up" aria-label="请选择要查看的订单">
      <div className="order-choice-panel__title">
        <span>选择一笔订单继续处理</span>
        <small>已为您查询最近订单，无需手动输入订单号</small>
      </div>
      <div className="order-choice-list">
        {choices.map(choice => {
          const isSelected = selectedOrderId === choice.orderId;
          const isDisabled = Boolean(selectedOrderId) || disabled;
          return (
            <button
              key={choice.orderId}
              type="button"
              className={`order-choice-card${isSelected ? ' order-choice-card--selected' : ''}`}
              aria-label={`选择订单 ${choice.orderId}`}
              disabled={isDisabled}
              onClick={() => handleSelect(choice.orderId)}
            >
              <span className="order-choice-card__radio">{isSelected ? '✓' : ''}</span>
              <span className="order-choice-card__content">
                <strong>{choice.title}</strong>
                <span>{choice.orderId}</span>
                {choice.details.length > 0 && (
                  <small className="order-choice-card__details">
                    {choice.details.map(detail => <em key={detail}>{detail}</em>)}
                  </small>
                )}
              </span>
              <span className="order-choice-card__action">{isSelected ? '已选择' : '选择'}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
