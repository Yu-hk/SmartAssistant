#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MinerU sidecar（生产就绪参考实现）。

协议（v1 进程协议，stdin/stdout JSON over line）：
    请求（每行一个 JSON）：
        {"pdf": "/abs/f.pdf", "pages": "all", "request_id": "u", "images_dir": "/tmp/mineru/u"}
    响应（每行一个 JSON）：
        {"status": "ok", "request_id": "u", "code": "OK",
         "pages": [{"page_no": 1, "blocks": [
             {"type": "text",  "text": "..."},
             {"type": "table", "text": "|a|b|", "table_caption": "表1"},
             {"type": "image", "image_path": "x.jpg", "image_caption": "图述", "text": "ocr字"}
         ]}]}
    错误响应：
        {"status": "error", "request_id": "u", "code": "MINERU_NOT_INSTALLED|MINERU_TIMEOUT|MINERU_NO_OUTPUT|MINERU_PARSE_ERROR", "pages": [], "message": "..."}

实现要点：
    1. 调 magic-pdf 把 PDF 解析为结构化 layout（content_list.json）。
    2. 把 content_list 按块类型（text/table/image）归一成上面的 schema。
    3. 图片抽取到 images_dir，image_path 仅存相对（basename），Java 端只存路径不存字节。

magic-pdf 版本兼容（已对齐 1.3.x）：
    - 1.3.x CLI 无 `pdf` 子命令，直接 `magic-pdf -p <pdf> -o <dir>`（旧版曾有 `magic-pdf pdf -p ...`，已移除）。
    - content_list 输出目录结构随版本变化，本脚本做多候选兜底查找。
    - category_type 取值随版本变化（text/title/paragraph/table/image/figure/equation），统一归一。
    若贵司 magic-pdf 版本字段名不同，改下方 normalize_content_list 的字段取值即可，无需动协议。

依赖：magic-pdf（pip install magic-pdf[full]）+ 模型权重（见 magic-pdf 文档）。
"""

import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [mineru-sidecar] %(message)s",
)
log = logging.getLogger("mineru-sidecar")


class MinerUError(Exception):
    """带错误码的 MinerU 解析异常，便于 Java 端按 code 分类处理。"""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


# magic-pdf 1.3.x 常见 CLI：无 `pdf` 子命令，直接 -p/-o。
# 旧版曾有 `magic-pdf pdf -p ...`，已在 1.3 移除；这里用 1.3 形态，兼容性最佳。
def _resolve_magic_pdf_cli():
    """定位 magic-pdf 可执行：优先同 venv 目录，回退 PATH。"""
    mp = os.path.join(os.path.dirname(sys.executable), "magic-pdf.exe")
    if os.path.exists(mp):
        return mp
    mp_sh = os.path.join(os.path.dirname(sys.executable), "magic-pdf")
    if os.path.exists(mp_sh):
        return mp_sh
    return "magic-pdf"


def parse_with_magic_pdf(pdf_path, images_dir):
    """调用 magic-pdf 解析 PDF，返回 content_list.json 绝对路径。

    对常见失败做分类（MINERU_NOT_INSTALLED / MINERU_TIMEOUT / MINERU_NO_OUTPUT）。
    """
    if not pdf_path or not os.path.exists(pdf_path):
        raise MinerUError("MINERU_NO_OUTPUT", "PDF 不存在: %s" % pdf_path)

    output_dir = tempfile.mkdtemp(prefix="mineru_out_")
    mp = _resolve_magic_pdf_cli()
    cmd = [mp, "-p", pdf_path, "-o", output_dir]
    log.info("调用 magic-pdf: %s", " ".join(cmd))
    try:
        t0 = time.time()
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=600)
        log.info("magic-pdf 完成, 耗时 %.1fs", time.time() - t0)
    except FileNotFoundError:
        raise MinerUError("MINERU_NOT_INSTALLED",
                          "magic-pdf 未安装或未在 PATH/venv 中（期望: %s）" % mp)
    except subprocess.CalledProcessError as e:
        raise MinerUError("MINERU_PARSE_ERROR", "magic-pdf 解析失败: %s" % e)
    except subprocess.TimeoutExpired:
        raise MinerUError("MINERU_TIMEOUT", "magic-pdf 解析超时(>600s)")

    # content_list.json 位置随版本变化，做多候选兜底查找：
    #   常见: <output>/<name>/<name>/content_list.json
    #   其他: <output>/<name>/content_list.json
    #   兜底: 递归扫描 output_dir 下任意 content_list.json
    name = os.path.splitext(os.path.basename(pdf_path))[0]
    candidates = [
        os.path.join(output_dir, name, name, "content_list.json"),
        os.path.join(output_dir, name, "content_list.json"),
        os.path.join(output_dir, "content_list.json"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    for root, _dirs, files in os.walk(output_dir):
        if "content_list.json" in files:
            return os.path.join(root, "content_list.json")
    raise MinerUError("MINERU_NO_OUTPUT", "magic-pdf 未产出 content_list.json")


def _resolve_image_src(content_list_path, image_path):
    """把 magic-pdf 给出的（可能相对的）图片路径解析为真实文件。

    magic-pdf 把图片放在 <output>/<name>/images/ 下，content_list 中 image_path
    常为相对引用（如 'images/xxx.jpg'）。多候选兜底，命中第一个存在的。
    """
    if not image_path:
        return None
    if os.path.isabs(image_path) and os.path.exists(image_path):
        return image_path
    base_dir = os.path.dirname(content_list_path)
    probes = [
        image_path,
        os.path.join(base_dir, image_path),
        os.path.join(base_dir, os.path.basename(image_path)),
        os.path.join(base_dir, "images", os.path.basename(image_path)),
        os.path.join(base_dir, "..", "images", os.path.basename(image_path)),
    ]
    for p in probes:
        if p and os.path.exists(p):
            return os.path.abspath(p)
    return None


def _copy_image_to(src_abs, images_dir):
    """拷贝图片到 images_dir，返回相对 images_dir 的 basename（Java 端据此拼路径）。"""
    if not src_abs or not os.path.exists(src_abs):
        return None
    os.makedirs(images_dir, exist_ok=True)
    dest = os.path.join(images_dir, os.path.basename(src_abs))
    if os.path.abspath(src_abs) != os.path.abspath(dest):
        try:
            shutil.copy(src_abs, dest)
        except Exception as e:
            log.warning("图片拷贝失败(%s): %s", src_abs, e)
            return os.path.basename(src_abs)
    return os.path.basename(dest)


def normalize_content_list(content_list, images_dir, content_list_path=None):
    """把 magic-pdf 的 content_list.json 归一成设计响应 schema。

    content_list 为 JSON 数组，元素含 category_type / page_idx / text / image_path 等。
    字段取值随 magic-pdf 版本略有差异，这里做兼容取值（text/table/image 三类归一并
    保留 caption / table_caption）。
    """
    pages = {}
    for item in content_list:
        if not isinstance(item, dict):
            continue
        # 页码：magic-pdf 用 0-based page_idx，+1 转 1-based
        page_no = int(item.get("page_idx", item.get("page_num", 0))) + 1
        cat = (item.get("category_type") or item.get("type") or "text").lower()
        block = None

        if cat in ("text", "title", "paragraph", "equation"):
            # equation 归一为 text（Java 端仅 text/table/image 三类；公式以文本(LaTeX)入索引）
            block = {"type": "text", "text": item.get("text", "")}
        elif cat in ("table",):
            block = {
                "type": "table",
                "text": item.get("text", ""),
                "table_caption": item.get("table_caption") or item.get("caption"),
            }
        elif cat in ("image", "picture", "figure"):
            text = item.get("text", "")
            img_path = item.get("image_path") or item.get("img_path")
            src = _resolve_image_src(content_list_path, img_path) if content_list_path else (
                img_path if (img_path and os.path.exists(img_path)) else None)
            rel = _copy_image_to(src, images_dir) if src else None
            block = {
                "type": "image",
                "image_path": rel,
                "image_caption": item.get("image_caption") or item.get("caption"),
                "text": text,
            }
        # 其他未知 category 忽略（不产出块）

        if block is not None:
            pages.setdefault(page_no, []).append(block)

    return [{"page_no": p, "blocks": blocks}
            for p, blocks in sorted(pages.items())]


def handle_request(req):
    pdf = req.get("pdf")
    request_id = req.get("request_id")
    images_dir = req.get("images_dir") or tempfile.mkdtemp(prefix="mineru_img_")
    try:
        t0 = time.time()
        content_list_path = parse_with_magic_pdf(pdf, images_dir)
        with open(content_list_path, "r", encoding="utf-8") as f:
            content_list = json.load(f)
        pages = normalize_content_list(content_list, images_dir, content_list_path)
        log.info("请求 %s 解析成功: pages=%d, 耗时 %.1fs",
                 request_id, len(pages), time.time() - t0)
        return {"status": "ok", "code": "OK",
                "request_id": request_id, "pages": pages}
    except MinerUError as e:
        log.error("请求 %s 失败[%s]: %s", request_id, e.code, e)
        return {"status": "error", "code": e.code, "request_id": request_id,
                "pages": [], "message": str(e)}
    except Exception as e:  # noqa: BLE001 - 兜底，避免 sidecar 进程崩溃
        log.exception("请求 %s 未知错误", request_id)
        return {"status": "error", "code": "MINERU_PARSE_ERROR",
                "request_id": request_id, "pages": [], "message": str(e)}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            out = {"status": "error", "code": "BAD_JSON",
                   "request_id": None, "pages": [], "message": "bad json"}
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        out = handle_request(req)
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
