import React, { useState } from 'react';
import { getOrderFollowUpSuggestions } from '../utils/orderFollowUps';

interface FollowUpSuggestionPanelProps {
  content: string;
  disabled?: boolean;
  onSelect: (suggestion: string) => void;
}

export function FollowUpSuggestionPanel({ content, disabled, onSelect }: FollowUpSuggestionPanelProps) {
  const [selectedSuggestion, setSelectedSuggestion] = useState<string | null>(null);
  const suggestions = getOrderFollowUpSuggestions(content);
  if (suggestions.length === 0) return null;

  const handleSelect = (suggestion: string) => {
    if (disabled || selectedSuggestion) return;
    setSelectedSuggestion(suggestion);
    onSelect(suggestion);
  };

  return (
    <div className="follow-up-panel animate-fade-in-up" aria-label="您还可以继续咨询">
      <span className="follow-up-panel__label">接下来您可以</span>
      <div className="follow-up-list">
        {suggestions.map(suggestion => {
          const isSelected = selectedSuggestion === suggestion;
          return (
            <button
              key={suggestion}
              type="button"
              className={`follow-up-chip${isSelected ? ' follow-up-chip--selected' : ''}`}
              aria-label={`继续咨询：${suggestion}`}
              disabled={Boolean(selectedSuggestion) || disabled}
              onClick={() => handleSelect(suggestion)}
            >
              {suggestion}
            </button>
          );
        })}
      </div>
    </div>
  );
}
