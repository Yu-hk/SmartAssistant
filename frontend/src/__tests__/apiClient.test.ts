/**
 * API Client 单元测试
 */
import { describe, it, expect, vi } from 'vitest';
import { apiClient, ApiError } from '../api/client';

describe('apiClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('ApiError', () => {
    it('应正确创建 ApiError', () => {
      const error = new ApiError(404, 'Not Found', 'body');
      expect(error.status).toBe(404);
      expect(error.message).toBe('Not Found');
      expect(error.body).toBe('body');
      expect(error.name).toBe('ApiError');
    });
  });

  describe('get', () => {
    it('应抛出 401 错误', async () => {
      vi.spyOn(global, 'fetch').mockResolvedValueOnce(
        new Response(null, { status: 401, statusText: 'Unauthorized' })
      );
      await expect(apiClient.get('/sessions')).rejects.toThrow(ApiError);
    });

    it('应成功返回 JSON', async () => {
      const mockData = { sessions: [] };
      vi.spyOn(global, 'fetch').mockResolvedValueOnce(
        new Response(JSON.stringify(mockData), { status: 200 })
      );
      const result = await apiClient.get('/sessions');
      expect(result).toEqual(mockData);
    });
  });

  describe('post', () => {
    it('应发送 POST 请求并返回数据', async () => {
      const mockData = { reply: 'hello' };
      vi.spyOn(global, 'fetch').mockResolvedValueOnce(
        new Response(JSON.stringify(mockData), { status: 200 })
      );
      const result = await apiClient.post('/chat', { message: 'hi' });
      expect(result).toEqual(mockData);
      expect(fetch).toHaveBeenCalledWith('/api/chat', expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ message: 'hi' }),
      }));
    });
  });

  describe('del', () => {
    it('204 应返回 undefined', async () => {
      vi.spyOn(global, 'fetch').mockResolvedValueOnce(
        new Response(null, { status: 204 })
      );
      const result = await apiClient.del('/sessions/1');
      expect(result).toBeUndefined();
    });
  });
});
