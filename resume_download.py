"""
支持断点续传的 BGE 模型下载器
自动从上次中断处继续，流式写入磁盘，防超时中断。
"""
import os, sys, time

URL = "https://hf-mirror.com/BAAI/bge-large-zh-v1.5/resolve/main/pytorch_model.bin"
OUTPUT = "D:/workspace/SmartAssistant/models/bge-large-zh-v1.5/pytorch_model.bin"
CHUNK_SIZE = 8192
PROGRESS_INTERVAL = 10  # 每下载这么多 MB 打印一次进度

# 确保目录存在
os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)

import requests

# 删除旧的不完整文件（如果有）
local_size = os.path.getsize(OUTPUT) if os.path.exists(OUTPUT) else 0
if local_size > 0 and local_size < 100 * 1024 * 1024:  # 小于100MB的文件可能不完整
    os.remove(OUTPUT)
    local_size = 0
    print(f"[*] 已删除旧的不完整文件，重新下载...")
elif local_size > 0:
    print(f"[*] 本地已有 {local_size / (1024*1024):.1f} MB，从断点继续...")

# 直接 GET 流式下载，不依赖 HEAD 请求
print(f"[*] 正在连接服务器并开始下载...")
headers = {"Range": f"bytes={local_size}-"} if local_size > 0 else {}
response = requests.get(URL, stream=True, headers=headers, timeout=(30, 120))  # (connect_timeout, read_timeout)

if response.status_code == 206:
    remote_size = int(response.headers.get("content-length", 0)) + local_size
    print(f"[✓] 支持断点续传，远程总大小: {remote_size / (1024*1024):.1f} MB")
elif response.status_code == 200:
    remote_size = int(response.headers.get("content-length", 0))
    print(f"[*] 全新下载，远程大小: {remote_size / (1024*1024):.1f} MB")
    local_size = 0
else:
    print(f"[✗] 服务器返回异常状态码: {response.status_code}")
    sys.exit(1)

# 打开文件（追加模式）
mode = "ab" if local_size > 0 else "wb"
downloaded = local_size
last_report_mb = downloaded / (1024*1024) // PROGRESS_INTERVAL * PROGRESS_INTERVAL if PROGRESS_INTERVAL > 0 else 0

with open(OUTPUT, mode) as f:
    for chunk in response.iter_content(chunk_size=CHUNK_SIZE):
        if chunk:
            f.write(chunk)
            f.flush()
            downloaded += len(chunk)
            
            # 每 10MB 报告一次进度
            current_mb_block = downloaded / (1024*1024) // PROGRESS_INTERVAL * PROGRESS_INTERVAL
            if current_mb_block > last_report_mb:
                progress = downloaded / remote_size * 100
                print(f"  进度: {progress:.1f}% ({downloaded/(1024*1024):.1f} MB / {remote_size/(1024*1024):.1f} MB)")
                last_report_mb = current_mb_block

print(f"\n[✓] 下载完成！")
print(f"   路径: {OUTPUT}")
print(f"   大小: {os.path.getsize(OUTPUT) / (1024*1024):.1f} MB")
