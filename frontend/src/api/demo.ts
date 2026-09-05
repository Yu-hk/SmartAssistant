import { register, type AuthUser } from './auth';

let pendingDemo: Promise<AuthUser> | null = null;

/** Each visit uses the existing registration contract and its server-enforced ROLE_USER. */
export function createDemoAccount(): Promise<AuthUser> {
  if (pendingDemo) return pendingDemo;
  const randomHex = () => Array.from(crypto.getRandomValues(new Uint8Array(16)),
    byte => byte.toString(16).padStart(2, '0')).join('');

  // The generated password is never displayed, persisted or shared with another visitor.
  pendingDemo = register(`demo_${randomHex()}`, randomHex(), '')
    .then(user => {
      if (user.role !== 'ROLE_USER') throw new Error('演示账号权限异常，请使用普通账号登录');
      return user;
    })
    .finally(() => { pendingDemo = null; });
  return pendingDemo;
}
