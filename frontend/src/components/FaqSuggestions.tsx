import React, { useState } from 'react';
import { FaqItem } from '../types';

interface FaqSuggestionsProps {
  faqs: FaqItem[];
  onSelect: (faq: FaqItem) => void;
  onDismiss: () => void;
}

export function FaqSuggestions({ faqs, onSelect, onDismiss }: FaqSuggestionsProps) {
  const [dismissed, setDismissed] = useState(false);

  if (dismissed || faqs.length === 0) return null;

  const handleDismiss = () => {
    setDismissed(true);
    onDismiss();
  };

  return (
    <div className="glass" style={{
      margin: '0 0 14px',
      padding: '14px 16px',
      borderRadius: '12px',
      border: '1px solid rgba(99, 102, 241, 0.2)',
      background: 'var(--nova-accent-light)',
      backdropFilter: 'blur(12px)',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        marginBottom: '10px',
      }}>
        <span style={{
          fontSize: '12px', fontWeight: 600,
          color: 'var(--nova-accent)',
          display: 'flex', alignItems: 'center', gap: '6px',
        }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round"
            strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          相关知识库参考
        </span>
        <button
          onClick={handleDismiss}
          style={{
            background: 'none', border: 'none', cursor: 'pointer',
            color: 'var(--nova-text-tertiary)', fontSize: '16px',
            padding: '0 4px', lineHeight: 1,
          }}
        >×</button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        {faqs.map(faq => (
          <button
            key={faq.id}
            onClick={() => { onSelect(faq); handleDismiss(); }}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: '10px',
              padding: '10px 14px',
              borderRadius: '10px',
              border: '1px solid var(--nova-border)',
              background: 'var(--nova-bg-glass)',
              cursor: 'pointer',
              textAlign: 'left',
              transition: 'all 0.2s',
              width: '100%',
              backdropFilter: 'blur(8px)',
            }}
            onMouseEnter={e => {
              (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
              (e.currentTarget as HTMLElement).style.background = 'var(--nova-bg-glass-hover)';
            }}
            onMouseLeave={e => {
              (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
              (e.currentTarget as HTMLElement).style.background = 'var(--nova-bg-glass)';
            }}
          >
            <div style={{
              width: '24px', height: '24px', borderRadius: '6px',
              background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: '12px', flexShrink: 0, color: 'white',
            }}>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"
                strokeLinejoin="round">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{
                fontSize: '13px', fontWeight: 600,
                color: 'var(--nova-accent)', marginBottom: '2px',
              }}>
                {faq.question}
              </div>
              <div style={{
                fontSize: '11px',
                color: 'var(--nova-text-secondary)',
                overflow: 'hidden', textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}>
                {faq.answer}
              </div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
