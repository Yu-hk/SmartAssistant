import React from 'react';
import { Wrench } from 'lucide-react';
import type { Message } from '../types';
import { summarizeTelemetry } from '../utils/sessionTelemetry';
import { getToolCapabilityLabel } from '../utils/toolDisplay';

/** Shared by the right sidebar and the collapsed narrow-screen entry. */
export function SessionExecutionSteps({ messages, defaultOpen = true }: {
  messages: Message[];
  defaultOpen?: boolean;
}) {
  const telemetry = summarizeTelemetry(messages);
  if (!telemetry.calls.length) return null;
  const statuses = { running: '执行中', completed: '已完成', error: '失败' };
  return (
    <details className="insight-tools" open={defaultOpen}>
      <summary>执行步骤（{telemetry.calls.length} 次调用）</summary>
      <ol aria-label="当前会话执行步骤" aria-live="polite">
        {telemetry.calls.map(tool => (
          <li key={tool.key}>
            <div><Wrench size={12} /><strong>{getToolCapabilityLabel(tool.name)}</strong>
              <span className={`insight-tool-status is-${tool.status}`}>
                {tool.status === 'running' && !telemetry.streaming ? '状态未回传' : statuses[tool.status]}
              </span></div>
            <small>第 {tool.turn} 轮{tool.durationMs !== undefined
              ? ` · ${tool.durationMs.toLocaleString('zh-CN')} ms` : ''}</small>
          </li>
        ))}
      </ol>
    </details>
  );
}
