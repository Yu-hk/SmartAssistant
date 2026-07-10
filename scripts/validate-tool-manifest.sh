#!/usr/bin/env bash
# ============================================================================
# SmartAssistant — Tool Manifest 校验辅助脚本
# ============================================================================
# 用途：在 CI 中校验工具注册 JSON/YAML 的基本格式
# 用法：./scripts/validate-tool-manifest.sh [manifest-dir]
#   manifest-dir: 工具 manifest 文件目录（默认: ./manifests）
# 退出码：0=全部通过, 1=存在校验失败
# ============================================================================

set -euo pipefail

MANIFEST_DIR="${1:-./manifests}"
PASS_COUNT=0
FAIL_COUNT=0

echo "=========================================="
echo "SmartAssistant Tool Manifest Validator"
echo "=========================================="
echo "Target directory: ${MANIFEST_DIR}"
echo ""

# 检查目录是否存在
if [ ! -d "${MANIFEST_DIR}" ]; then
    echo "[WARN] Manifest 目录不存在: ${MANIFEST_DIR}"
    echo "[INFO] 跳过 manifest 校验（无文件需要检查）"
    exit 0
fi

# 查找所有 JSON 和 YAML 文件
MANIFEST_FILES=$(find "${MANIFEST_DIR}" -type f \( -name "*.json" -o -name "*.yaml" -o -name "*.yml" \) 2>/dev/null || true)

if [ -z "${MANIFEST_FILES}" ]; then
    echo "[INFO] 未找到 manifest 文件，跳过校验"
    exit 0
fi

# 校验单个 JSON 文件
validate_json() {
    local file="$1"
    if command -v jq >/dev/null 2>&1; then
        if jq empty "${file}" 2>/dev/null; then
            echo "[PASS] ${file}"
            return 0
        else
            echo "[FAIL] ${file} — JSON 格式错误"
            return 1
        fi
    else
        # 降级：使用 python 校验
        if python3 -c "import json,sys; json.load(open(sys.argv[1]))" "${file}" 2>/dev/null; then
            echo "[PASS] ${file}"
            return 0
        else
            echo "[FAIL] ${file} — JSON 格式错误"
            return 1
        fi
    fi
}

# 校验单个 YAML 文件
validate_yaml() {
    local file="$1"
    if python3 -c "
import sys
try:
    import yaml
    yaml.safe_load(open(sys.argv[1]))
except ImportError:
    # PyYAML 不可用时降级为基本格式检查
    content = open(sys.argv[1]).read()
    if not content.strip():
        print('Empty file')
        sys.exit(1)
except Exception:
    sys.exit(1)
" "${file}" 2>/dev/null; then
        echo "[PASS] ${file}"
        return 0
    else
        echo "[FAIL] ${file} — YAML 格式错误"
        return 1
    fi
}

# 遍历校验
while IFS= read -r file; do
    ext="${file##*.}"
    case "${ext}" in
        json)
            if validate_json "${file}"; then
                PASS_COUNT=$((PASS_COUNT + 1))
            else
                FAIL_COUNT=$((FAIL_COUNT + 1))
            fi
            ;;
        yaml|yml)
            if validate_yaml "${file}"; then
                PASS_COUNT=$((PASS_COUNT + 1))
            else
                FAIL_COUNT=$((FAIL_COUNT + 1))
            fi
            ;;
    esac
done <<< "${MANIFEST_FILES}"

echo ""
echo "=========================================="
echo "Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
echo "=========================================="

if [ "${FAIL_COUNT}" -gt 0 ]; then
    exit 1
fi

exit 0
