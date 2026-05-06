"""
DataGifTool 等价测试 - Python 版
模拟 Agent 调用链路：查数据库 → 渲染图表 → 生成 GIF
"""
import subprocess, json, base64, io
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.dates import DateFormatter
from datetime import datetime, timedelta

# 1. 查数据库
result = subprocess.run(
    ['psql', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'a2a_system',
     '-t', '-A', '-F', ',',
     '-c', "SELECT DATE(created_at), COUNT(*)::int FROM users "
           "WHERE created_at >= NOW() - INTERVAL '30 days' "
           "GROUP BY DATE(created_at) ORDER BY date;"],
    capture_output=True, text=True,
    env={**__import__('os').environ, 'PGPASSWORD': 'postgres123'}
)

dates, values = [], []
for line in result.stdout.strip().split('\n'):
    if line:
        d, v = line.split(',')
        dates.append(datetime.strptime(d, '%Y-%m-%d'))
        values.append(int(v))

print(f"✅ 数据库查询成功: {len(dates)} 个数据点")
print(f"   日期范围: {dates[0].strftime('%m/%d')} ~ {dates[-1].strftime('%m/%d')}")

# 2. 渲染逐帧图表 → GIF
frames = []
for i in range(1, len(dates) + 1):
    fig, ax = plt.subplots(figsize=(8, 4.8))
    fig.patch.set_facecolor('white')
    ax.set_facecolor('#F8FAFC')

    sub_dates = dates[:i]
    sub_vals = values[:i]

    ax.plot(sub_dates, sub_vals, color='#3182CE', linewidth=2, marker='o', markersize=6)
    ax.fill_between(sub_dates, sub_vals, alpha=0.1, color='#3182CE')

    ax.set_title('近30天用户增长趋势', fontsize=14, fontweight='bold', pad=15)
    ax.set_xlabel('日期', fontsize=11)
    ax.set_ylabel('新增用户数', fontsize=11)
    ax.set_ylim(0, max(values) * 1.2 + 1)
    ax.grid(True, alpha=0.3)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)

    # 显示最后一个数据点的值
    ax.annotate(f'{sub_vals[-1]}', (sub_dates[-1], sub_vals[-1]),
                textcoords="offset points", xytext=(0, 10),
                ha='center', fontsize=10, color='#3182CE')

    date_fmt = DateFormatter('%m/%d')
    ax.xaxis.set_major_formatter(date_fmt)
    fig.autofmt_xdate()

    # 转 PNG 字节
    buf = io.BytesIO()
    fig.savefig(buf, format='png', dpi=100, bbox_inches='tight')
    buf.seek(0)
    frames.append(buf)
    plt.close(fig)

print(f"✅ 渲染完成: {len(frames)} 帧")

# 3. 合成 GIF (用 matplotlib.animation)
from matplotlib.animation import FuncAnimation, PillowWriter

fig2, ax2 = plt.subplots(figsize=(8, 4.8))
fig2.patch.set_facecolor('white')

def update(frame_idx):
    ax2.clear()
    ax2.set_facecolor('#F8FAFC')
    sub_dates = dates[:frame_idx + 1]
    sub_vals = values[:frame_idx + 1]
    ax2.plot(sub_dates, sub_vals, color='#3182CE', linewidth=2, marker='o', markersize=6)
    ax2.fill_between(sub_dates, sub_vals, alpha=0.1, color='#3182CE')
    ax2.set_title('近30天用户增长趋势', fontsize=14, fontweight='bold', pad=15)
    ax2.set_xlabel('日期', fontsize=11)
    ax2.set_ylabel('新增用户数', fontsize=11)
    ax2.set_ylim(0, max(values) * 1.2 + 1)
    ax2.grid(True, alpha=0.3)
    ax2.spines['top'].set_visible(False)
    ax2.spines['right'].set_visible(False)
    ax2.annotate(f'{sub_vals[-1]}', (sub_dates[-1], sub_vals[-1]),
                textcoords="offset points", xytext=(0, 10),
                ha='center', fontsize=10, color='#3182CE')
    date_fmt = DateFormatter('%m/%d')
    ax2.xaxis.set_major_formatter(date_fmt)
    fig2.autofmt_xdate()

ani = FuncAnimation(fig2, update, frames=len(dates), interval=400, repeat=True)
writer = PillowWriter(fps=2.5)

output_path = 'target/test-user-growth.gif'
ani.save(output_path, writer=writer, dpi=100)
plt.close(fig2)

import os
size_kb = os.path.getsize(output_path) / 1024
print(f"\n✅ GIF 生成成功！")
print(f"   文件: {os.path.abspath(output_path)}")
print(f"   大小: {size_kb:.0f} KB")
print(f"   帧数: {len(dates)}")
print(f"\nBase64 data URI (前100字符):")
print(f"   data:image/gif;base64,... ")
print(f"   完整 Base64 保存在: {output_path}")
