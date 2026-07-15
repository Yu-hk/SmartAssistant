import React from 'react';

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
  errorInfo: React.ErrorInfo | null;
}

interface ErrorBoundaryProps {
  children: React.ReactNode;
  /** 自定义降级界面；不传则用内置样式 */
  fallback?: (error: Error, reset: () => void) => React.ReactNode;
}

/**
 * 全局错误边界：捕获子树渲染期异常，避免 SPA 整页白屏。
 * 注意：仅能捕获渲染/生命周期阶段的同步错误，无法捕获事件回调/异步（如 fetch、SSE onerror）中的异常。
 */
export class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null, errorInfo: null };
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    // 统一上报，便于监控侧定位前端崩溃
    this.setState({ errorInfo });
    console.error('[ErrorBoundary] 捕获到渲染期异常：', error, errorInfo);
  }

  private handleReset = (): void => {
    this.setState({ hasError: false, error: null, errorInfo: null });
  };

  private handleReload = (): void => {
    window.location.reload();
  };

  render(): React.ReactNode {
    const { hasError, error, errorInfo } = this.state;
    if (!hasError) {
      return this.props.children;
    }
    if (this.props.fallback) {
      return this.props.fallback(error!, this.handleReset);
    }
    return (
      <div
        style={{
          maxWidth: 640,
          margin: '80px auto',
          padding: 24,
          fontFamily: 'system-ui, sans-serif',
          color: '#e34d59',
          background: '#fff',
          borderRadius: 8,
          boxShadow: '0 2px 12px rgba(0,0,0,0.08)',
        }}
      >
        <h2 style={{ marginTop: 0 }}>页面出现错误</h2>
        <p style={{ color: '#555' }}>
          界面渲染时发生异常，已为你保留当前位置。可尝试恢复，或重新加载页面。
        </p>
        {error && (
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              background: '#f5f5f5',
              padding: 12,
              borderRadius: 6,
              fontSize: 12,
              color: '#d54941',
              maxHeight: 200,
              overflow: 'auto',
            }}
          >
            {error.message}
            {errorInfo?.componentStack}
          </pre>
        )}
        <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
          <button
            onClick={this.handleReset}
            style={{
              padding: '8px 16px',
              border: 'none',
              borderRadius: 4,
              background: '#0052d9',
              color: '#fff',
              cursor: 'pointer',
            }}
          >
            尝试恢复
          </button>
          <button
            onClick={this.handleReload}
            style={{
              padding: '8px 16px',
              border: '1px solid #ddd',
              borderRadius: 4,
              background: '#fff',
              color: '#333',
              cursor: 'pointer',
            }}
          >
            重新加载
          </button>
        </div>
      </div>
    );
  }
}

export default ErrorBoundary;
