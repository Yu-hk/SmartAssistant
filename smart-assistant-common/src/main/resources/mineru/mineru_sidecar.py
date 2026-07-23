#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MinerU sidecar 参考实现（部署环境用，非生产完备，仅供集成联调）。

协议（v1 进程协议）：
    从 stdin 逐行读取 JSON 请求：
        {"pdf": "/abs/f.pdf", "pages": "all", "request_id": "u", "images_dir": "/tmp/mineru/u"}
    处理后将归一化响应写回 stdout（一行一个 JSON）：
        {"status": "ok", "request_id": "u",
         "pages": [{"page_no": 1, "blocks": [
             {"type": "text", "text": "..."},
             {"type": "table", "text": "|a|b|", "table_caption": "表1"},
             {"type": "image", "image_path": "i/x.jpg", "image_caption": "图述", "text": "ocr字"}
         ]}]}

实现要点：
    1. 调用 magic-pdf 将 PDF 解析为结构化 JSON layout（content_list.json）。
    2. 解析 content_list.json，按块类型（text/table/image）归一成上述 schema。
    3. 图片抽取到 images_dir，image_path 仅存相对路径（Java 端只存路径，不存字节）。

⚠️ 注意：magic-pdf 版本接口可能变化（CLI 子命令 / 输出目录结构 / content_list 字段名）。
    本参考脚本基于常见约定编写，部署时请按实际 magic-pdf 版本核对并调整 TODO 处。

依赖：magic-pdf（pip install magic-pdf[full]），以及模型权重（见 magic-pdf 文档）。
"""

import json
import os
import subprocess
import sys
import tempfile


def parse_with_magic_pdf(pdf_path, images_dir):
    """
    调用 magic-pdf 解析 PDF，返回 content_list.json 路径。

    TODO: magic-pdf 版本接口可能变化，请按实际版本调整以下 CLI 调用与输出路径。
    常见命令（示例，不同版本可能不同）：
        magic-pdf pdf -p <pdf_path> -o <output_dir>
    解析产物通常位于 <output_dir>/<pdf_name>/<pdf_name>/content_list.json
    """
    output_dir = tempfile.mkdtemp(prefix="mineru_out_")
    cmd = ["magic-pdf", "pdf", "-p", pdf_path, "-o", output_dir]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=600)
    except FileNotFoundError:
        raise RuntimeError("magic-pdf 未安装或未在 PATH 中")
    except subprocess.CalledProcessError as e:
        raise RuntimeError("magic-pdf 解析失败: %s" % e)
    except subprocess.TimeoutExpired:
        raise RuntimeError("magic-pdf 解析超时")

    # TODO: 按实际 magic-pdf 输出目录结构定位 content_list.json
    name = os.path.splitext(os.path.basename(pdf_path))[0]
    candidate = os.path.join(output_dir, name, name, "content_list.json")
    if not os.path.exists(candidate):
        # 兜底：扫描 output_dir 查找任意 content_list.json
        for root, _dirs, files in os.walk(output_dir):
            if "content_list.json" in files:
                candidate = os.path.join(root, "content_list.json")
                break
    if not os.path.exists(candidate):
        raise RuntimeError("未找到 content_list.json")
    return candidate


def normalize_content_list(content_list, images_dir):
    """
    将 magic-pdf 的 content_list.json 归一成设计响应 schema。
    content_list 为 JSON 数组，每个元素含 category_type / text / image_path 等。
    TODO: 按实际 magic-pdf content_list 字段映射下列判定逻辑。
    """
    pages = {}
    for item in content_list:
        page_no = int(item.get("page_idx", item.get("page_num", 0))) + 1
        cat = (item.get("category_type") or item.get("type") or "text").lower()
        block = None
        if cat in ("text", "title", "paragraph"):
            block = {"type": "text", "text": item.get("text", "")}
        elif cat in ("table",):
            block = {"type": "table",
                     "text": item.get("text", ""),
                     "table_caption": item.get("table_caption") or item.get("caption")}
        elif cat in ("image", "picture"):
            text = item.get("text", "")
            img_path = item.get("image_path") or item.get("img_path")
            # 将图片拷贝/移动到 images_dir，image_path 仅存相对路径
            rel = img_path
            if img_path and os.path.exists(img_path):
                try:
                    os.makedirs(images_dir, exist_ok=True)
                    dest = os.path.join(images_dir, os.path.basename(img_path))
                    if os.path.abspath(img_path) != os.path.abspath(dest):
                        import shutil
                        shutil.copy(img_path, dest)
                    rel = os.path.relpath(dest, images_dir)
                except Exception:
                    rel = img_path
            block = {"type": "image",
                     "image_path": rel,
                     "image_caption": item.get("image_caption") or item.get("caption"),
                     "text": text}
        if block is not None:
            pages.setdefault(page_no, []).append(block)

    return [{"page_no": p, "blocks": blocks} for p, blocks in sorted(pages.items())]


def handle_request(req):
    pdf = req.get("pdf")
    request_id = req.get("request_id")
    images_dir = req.get("images_dir") or tempfile.mkdtemp(prefix="mineru_img_")
    try:
        content_list_path = parse_with_magic_pdf(pdf, images_dir)
        with open(content_list_path, "r", encoding="utf-8") as f:
            content_list = json.load(f)
        pages = normalize_content_list(content_list, images_dir)
        return {"status": "ok", "request_id": request_id, "pages": pages}
    except Exception as e:
        return {"status": "error", "request_id": request_id, "pages": [],
                "message": str(e)}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            out = {"status": "error", "request_id": None, "pages": [], "message": "bad json"}
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        out = handle_request(req)
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
