import { useCallback, useEffect, useRef, useState } from 'react';
import { authenticatedFetch } from '../api/client';
import { notifications as notificationApi } from '../api';
import type { Session, UserNotification } from '../types';

interface UseNotificationsOptions {
  setSessions: React.Dispatch<React.SetStateAction<Session[]>>;
}

/** Durable inbox plus a reconnecting, authenticated SSE stream. */
export function useNotifications({ setSessions }: UseNotificationsOptions) {
  const [notifications, setNotifications] = useState<UserNotification[]>([]);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const applyNotification = useCallback((notification: UserNotification) => {
    setNotifications(previous => previous.some(item => item.id === notification.id)
      ? previous
      : [notification, ...previous]);
    if (notification.type !== 'WORKFLOW_RECOVERY' || !notification.requestId) return;
    setSessions(previous => previous.map(session => ({
      ...session,
      messages: session.messages.map(message => message.requestId === notification.requestId
        ? {
          ...message,
          content: notification.content,
          contentBlocks: [{ type: 'text' as const, text: notification.content }],
          isStreaming: false,
          deliveryStatus: 'completed' as const,
          recoverable: false,
          recoveryStatus: 'SUCCEEDED' as const,
          recoveryError: undefined,
        }
        : message),
    })));
  }, [setSessions]);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();

    void notificationApi.fetchUnread().then(items => {
      if (!active) return;
      setNotifications(items);
      items.forEach(applyNotification);
    }).catch(() => undefined);

    const connect = async () => {
      try {
        const response = await authenticatedFetch('/api/notifications/stream', {
          headers: { Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (active && !controller.signal.aborted) {
          const { done, value } = await reader.read();
          buffer = (buffer + decoder.decode(value, { stream: !done })).replace(/\r\n/g, '\n');
          let boundary = buffer.indexOf('\n\n');
          while (boundary >= 0) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const eventName = block.split('\n').find(line => line.startsWith('event:'))
              ?.slice(6).trim();
            const data = block.split('\n').filter(line => line.startsWith('data:'))
              .map(line => line.slice(5).trimStart()).join('\n');
            if (eventName === 'notification' && data) {
              try { applyNotification(JSON.parse(data) as UserNotification); } catch { /* ignore */ }
            }
            boundary = buffer.indexOf('\n\n');
          }
          if (done) break;
        }
      } catch {
        // A durable inbox is the source of truth; SSE reconnect is only the fast path.
      }
      if (active && !controller.signal.aborted) {
        reconnectTimer.current = setTimeout(connect, 3000);
      }
    };
    void connect();

    return () => {
      active = false;
      controller.abort();
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
    };
  }, [applyNotification]);

  const markRead = useCallback(async (notificationId: string) => {
    setNotifications(previous => previous.filter(item => item.id !== notificationId));
    await notificationApi.markRead(notificationId).catch(() => undefined);
  }, []);

  return { notifications, markRead };
}
