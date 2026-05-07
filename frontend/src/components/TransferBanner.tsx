import React from 'react';

interface TransferBannerProps {
  status: 'active' | 'human_transfer' | 'closed';
  onRequestTransfer: () => void;
}

export function TransferBanner({ status, onRequestTransfer }: TransferBannerProps) {
  if (status === 'human_transfer') {
    return (
      <div className="glass" style={{
        margin: '0 20px 12px',
        padding: '12px 18px',
        borderRadius: '12px',
        border: '1px solid rgba(245, 158, 11, 0.3)',
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        backdropFilter: 'blur(12px)',
        boxShadow: '0 0 20px rgba(245, 158, 11, 0.1)',
      }}>
        <div style={{
          width: '32px', height: '32px', borderRadius: '10px',
          background: 'linear-gradient(135deg, #f59e0b, #d97706)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '16px', flexShrink: 0,
        }}>
          👨‍💼
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: '13px', fontWeight: 600, color: '#f59e0b' }}>已转接旅行规划师</div>
          <div style={{ fontSize: '12px', color: 'var(--nova-text-tertiary)', marginTop: '2px' }}>
            专业旅行顾问工作时间：工作日 9:00-21:00。请稍候，我们将尽快为您服务。
          </div>
        </div>
        <div style={{
          padding: '4px 12px',
          borderRadius: '100px',
          background: 'linear-gradient(135deg, #f59e0b, #d97706)',
          color: '#fff',
          fontSize: '12px',
          fontWeight: 500,
          whiteSpace: 'nowrap',
          boxShadow: '0 0 12px rgba(245, 158, 11, 0.4)',
        }}>
          排队中...
        </div>
      </div>
    );
  }

  if (status === 'closed') {
    return (
      <div className="glass" style={{
        margin: '0 20px 12px',
        padding: '12px 18px',
        borderRadius: '12px',
        border: '1px solid rgba(16, 185, 129, 0.3)',
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        boxShadow: '0 0 20px rgba(16, 185, 129, 0.1)',
      }}>
        <div style={{
          width: '32px', height: '32px', borderRadius: '10px',
          background: 'linear-gradient(135deg, #10b981, #059669)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '16px', flexShrink: 0,
        }}>
          ✓
        </div>
        <div style={{ fontSize: '13px', color: 'var(--nova-success)', fontWeight: 500 }}>
          本次对话已结束。如有新问题，请开启新对话。
        </div>
      </div>
    );
  }

  return (
    <div className="glass" style={{
      margin: '0 20px 12px',
      padding: '8px 16px',
      borderRadius: '10px',
      border: '1px solid var(--nova-border)',
      display: 'flex',
      alignItems: 'center',
      gap: '10px',
    }}>
      <div style={{
        width: '24px', height: '24px', borderRadius: '6px',
        background: 'linear-gradient(135deg, var(--nova-accent), var(--nova-secondary))',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '12px', flexShrink: 0,
      }}>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
          <path d="M7 11V7a5 5 0 0 1 10 0v4" />
        </svg>
      </div>
      <span style={{ flex: 1, fontSize: '12px', color: 'var(--nova-text-secondary)' }}>
        AI 旅行助手为您服务。如需专业旅行顾问，请点击右侧按钮
      </span>
      <button
        onClick={onRequestTransfer}
        style={{
          padding: '5px 14px',
          borderRadius: '100px',
          border: '1px solid var(--nova-accent)',
          background: 'transparent',
          color: 'var(--nova-accent)',
          fontSize: '12px',
          cursor: 'pointer',
          whiteSpace: 'nowrap',
          fontWeight: 500,
          transition: 'all 0.2s',
        }}
        onMouseEnter={e => {
          (e.target as HTMLElement).style.background = 'var(--nova-accent)';
          (e.target as HTMLElement).style.color = 'white';
          (e.target as HTMLElement).style.boxShadow = '0 0 16px var(--nova-accent-glow)';
        }}
        onMouseLeave={e => {
          (e.target as HTMLElement).style.background = 'transparent';
          (e.target as HTMLElement).style.color = 'var(--nova-accent)';
          (e.target as HTMLElement).style.boxShadow = 'none';
        }}
      >
        联系顾问
      </button>
    </div>
  );
}
