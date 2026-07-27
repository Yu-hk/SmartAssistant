#!/usr/bin/env bash
#
# setup-branch-protection.sh
# ---------------------------------------------------------------
# 将 SmartAssistant 仓库 main 分支的两个 CI 卡点设为「必需状态检查」，
# 从而在 PR 合入前真正阻断质量回归（P0-④ 闭环）。
#
# 前置：本机已通过 `gh auth login` 登录 GitHub，且账号对 Yu-hk/SmartAssistant
#       拥有 admin 权限（个人仓库的 owner 默认具备）。
#
# GitHub Actions 的 App ID 在 github.com 上为 15368（已硬编码）。
# 若贵司使用 GitHub Enterprise Server，请将 15368 替换为对应实例的
# Actions App ID（可在 check-runs 接口中查到）。
# ---------------------------------------------------------------
set -euo pipefail

OWNER="Yu-hk"
REPO="SmartAssistant"
BRANCH="main"
ACTIONS_APP_ID=15368

echo "==> 校验 gh 登录状态"
gh auth status || { echo "ERROR: 请先运行 'gh auth login' 登录 GitHub"; exit 1; }

echo "==> 为 ${OWNER}/${REPO}:${BRANCH} 设置分支保护（必需状态检查）"
gh api --method PUT "repos/${OWNER}/${REPO}/branches/${BRANCH}/protection" --input - <<JSON
{
  "required_status_checks": {
    "strict": true,
    "checks": [
      { "context": "Router E2E gate (mocked, no-infra)", "app_id": ${ACTIONS_APP_ID} },
      { "context": "Evaluation gate (golden suite)",       "app_id": ${ACTIONS_APP_ID} }
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON

echo "==> 校验写入结果"
gh api "repos/${OWNER}/${REPO}/branches/${BRANCH}/protection" \
  --jq '{ required_status_checks: .required_status_checks }'

echo "DONE. 两个必需状态检查已生效："
echo "  - Router E2E gate (mocked, no-infra)"
echo "  - Evaluation gate (golden suite)"
echo ""
echo "注意：当前策略仅要求状态检查通过，未强制 PR review。"
echo "若想彻底禁止直接 push main、强制所有变更走 PR，可将脚本中"
echo "required_pull_request_reviews 改为 { \"required_approving_review_count\": 1 } 并重新运行。"
