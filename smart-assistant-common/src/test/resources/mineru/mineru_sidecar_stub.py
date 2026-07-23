#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MinerU sidecar 桩实现（仅用于集成测试，验证客户端 stdio JSON 协议 / 超时 / 重试）。
不调用真实 magic-pdf，仅按请求回吐一个确定性响应，便于断言客户端协议正确性。

请求：{"pdf": "...", "pages": "all", "request_id": "u", "images_dir": "..."}
响应：{"status": "ok", "request_id": "u",
        "pages": [{"page_no": 1, "blocks": [
            {"type": "text", "text": "stub mineru text"},
            {"type": "image", "image_path": "i/x.jpg", "image_caption": "stub caption", "text": "stub ocr"}
        ]}]}
"""

import json
import sys


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
        rid = req.get("request_id")
        pdf = req.get("pdf")
        resp = {
            "status": "ok",
            "request_id": rid,
            "pages": [
                {
                    "page_no": 1,
                    "blocks": [
                        {"type": "text", "text": "stub mineru text for %s" % pdf},
                        {"type": "image", "image_path": "i/x.jpg",
                         "image_caption": "stub caption", "text": "stub ocr"}
                    ]
                }
            ]
        }
        sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
