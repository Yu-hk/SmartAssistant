import type { OAuthProviderStatus } from '../api/auth';

const LOGIN_CHANNELS = [
  { id: 'dingtalk', name: '钉钉', color: '#3370ff' },
  { id: 'feishu', name: '飞书', color: '#00d6b9' },
] as const;

// 接口只更新已展示渠道的可用状态，不自动增加登录入口。
export function getLoginChannels(providers: readonly OAuthProviderStatus[] = []) {
  return LOGIN_CHANNELS.map(channel => ({
    ...channel,
    enabled: providers.some(provider => provider.id === channel.id && provider.enabled === true),
  }));
}

export type LoginChannel = ReturnType<typeof getLoginChannels>[number];
