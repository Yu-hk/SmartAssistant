import { BellRing, ExternalLink, X } from 'lucide-react';
import type { UserNotification } from '../types';

interface RecoveryNotificationCenterProps {
  notifications: UserNotification[];
  onOpen: (notification: UserNotification) => void;
  onDismiss: (notificationId: string) => void;
}

export function RecoveryNotificationCenter({
  notifications,
  onOpen,
  onDismiss,
}: RecoveryNotificationCenterProps) {
  if (notifications.length === 0) return null;
  return (
    <aside className="recovery-notification-center" aria-live="polite" aria-label="恢复通知">
      {notifications.slice(0, 3).map(notification => (
        <article className="recovery-notification-card" key={notification.id}>
          <span className="recovery-notification-icon"><BellRing size={18} /></span>
          <div className="recovery-notification-body">
            <strong>{notification.title}</strong>
            <p>{notification.content}</p>
            {notification.sessionId && (
              <button type="button" onClick={() => onOpen(notification)}>
                查看恢复后的会话 <ExternalLink size={13} />
              </button>
            )}
          </div>
          <button
            type="button"
            className="recovery-notification-close"
            aria-label="标记通知为已读"
            onClick={() => onDismiss(notification.id)}
          >
            <X size={15} />
          </button>
        </article>
      ))}
    </aside>
  );
}
