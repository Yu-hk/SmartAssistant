import React, { useState } from 'react';

interface SatisfactionDialogProps {
  visible: boolean;
  onSubmit: (score: number, comment?: string) => void;
  onSkip: () => void;
}

const SCORES = [
  { value: 1, emoji: '😞', label: '非常不满意' },
  { value: 2, emoji: '😕', label: '不满意' },
  { value: 3, emoji: '😐', label: '一般' },
  { value: 4, emoji: '😊', label: '满意' },
  { value: 5, emoji: '🤩', label: '非常满意' },
];

export function SatisfactionDialog({ visible, onSubmit, onSkip }: SatisfactionDialogProps) {
  const [selected, setSelected] = useState<number | null>(null);
  const [comment, setComment] = useState('');
  const [hovered, setHovered] = useState<number | null>(null);

  if (!visible) return null;

  const handleSubmit = () => {
    if (!selected) return;
    onSubmit(selected, comment.trim() || undefined);
    setSelected(null);
    setComment('');
  };

  const displayScore = hovered || selected;

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(0,0,0,0.5)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1000,
      backdropFilter: 'blur(4px)',
    }}>
      <div className="animate-scale-in" style={{
        background: 'var(--nova-bg-elevated)',
        backdropFilter: 'blur(24px)',
        borderRadius: '20px',
        padding: '36px',
        width: '460px',
        maxWidth: '90vw',
        border: '1px solid var(--nova-border)',
        boxShadow: 'var(--nova-shadow-xl), 0 0 60px var(--nova-accent-glow)',
      }}>
        {/* 标题 */}
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <div style={{
            width: '56px', height: '56px', borderRadius: '16px',
            background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 16px',
            fontSize: '28px',
            boxShadow: '0 0 24px var(--nova-accent-glow)',
          }}>
            ⭐
          </div>
          <div style={{
            fontSize: '18px', fontWeight: 700,
            color: 'var(--nova-text-primary)',
          }}>
            本次服务评价
          </div>
          <div style={{
            fontSize: '13px', color: 'var(--nova-text-secondary)',
            marginTop: '6px',
          }}>
            您的反馈帮助我们持续改进服务质量
          </div>
        </div>

        {/* 评分 */}
        <div style={{ display: 'flex', justifyContent: 'center', gap: '10px', marginBottom: '20px' }}>
          {SCORES.map(s => (
            <button
              key={s.value}
              onClick={() => setSelected(s.value)}
              onMouseEnter={() => setHovered(s.value)}
              onMouseLeave={() => setHovered(null)}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '6px',
                padding: '12px 10px',
                borderRadius: '14px',
                border: selected === s.value
                  ? '2px solid var(--nova-accent)'
                  : '2px solid transparent',
                background: selected === s.value
                  ? 'var(--nova-accent-light)'
                  : hovered === s.value
                    ? 'var(--nova-bg-component-hover)'
                    : 'transparent',
                cursor: 'pointer',
                transition: 'all 0.2s',
                minWidth: '64px',
              }}
            >
              <span style={{
                fontSize: '30px',
                transition: 'transform 0.2s',
                transform: hovered === s.value || selected === s.value ? 'scale(1.2)' : 'scale(1)',
                display: 'block',
              }}>
                {s.emoji}
              </span>
              <span style={{
                fontSize: '10px',
                color: selected === s.value ? 'var(--nova-accent)' : 'var(--nova-text-tertiary)',
                fontWeight: selected === s.value ? 600 : 400,
              }}>
                {s.label}
              </span>
            </button>
          ))}
        </div>

        {/* 评分提示 */}
        {displayScore && (
          <div style={{
            textAlign: 'center',
            fontSize: '13px',
            color: 'var(--nova-accent)',
            marginBottom: '16px',
            fontWeight: 500,
          }}>
            {SCORES.find(s => s.value === displayScore)?.emoji}{' '}
            {SCORES.find(s => s.value === displayScore)?.label}
          </div>
        )}

        {/* 备注 */}
        <textarea
          placeholder="有什么建议或意见？（选填）"
          value={comment}
          onChange={e => setComment(e.target.value)}
          rows={3}
          style={{
            width: '100%',
            borderRadius: '12px',
            border: '1px solid var(--nova-border)',
            padding: '12px 14px',
            fontSize: '13px',
            resize: 'none',
            outline: 'none',
            background: 'var(--nova-bg-component)',
            color: 'var(--nova-text-primary)',
            boxSizing: 'border-box',
            marginBottom: '20px',
            fontFamily: 'inherit',
            transition: 'all 0.2s',
          }}
          onFocus={e => {
            e.target.style.borderColor = 'var(--nova-accent)';
            e.target.style.boxShadow = '0 0 16px var(--nova-accent-glow)';
          }}
          onBlur={e => {
            e.target.style.borderColor = 'var(--nova-border)';
            e.target.style.boxShadow = 'none';
          }}
        />

        {/* 操作按钮 */}
        <div style={{ display: 'flex', gap: '10px' }}>
          <button
            onClick={onSkip}
            style={{
              flex: 1,
              padding: '10px',
              borderRadius: '10px',
              border: '1px solid var(--nova-border)',
              background: 'transparent',
              color: 'var(--nova-text-secondary)',
              fontSize: '14px',
              cursor: 'pointer',
              transition: 'all 0.2s',
            }}
            onMouseEnter={e => {
              (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-accent-glow)';
            }}
            onMouseLeave={e => {
              (e.currentTarget as HTMLElement).style.borderColor = 'var(--nova-border)';
            }}
          >
            暂不评价
          </button>
          <button
            onClick={handleSubmit}
            disabled={!selected}
            style={{
              flex: 2,
              padding: '10px',
              borderRadius: '10px',
              border: 'none',
              background: selected
                ? 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))'
                : 'var(--nova-bg-component)',
              color: selected ? '#fff' : 'var(--nova-text-tertiary)',
              fontSize: '14px',
              fontWeight: 600,
              cursor: selected ? 'pointer' : 'not-allowed',
              transition: 'all 0.2s',
              boxShadow: selected ? '0 0 20px var(--nova-accent-glow)' : 'none',
            }}
          >
            提交评价
          </button>
        </div>
      </div>
    </div>
  );
}
