# Versioned Workflow DSL

The Router stores business workflows as immutable, versioned definitions and compiles a
published definition into the existing `IntentGraph`/LangGraph4j runtime. It does not add a
second orchestration engine.

## Lifecycle

1. Create a new draft version.
2. Validate the definition.
3. Publish the draft. Publication repeats validation and performs a real LangGraph4j compile
   without executing any business node.
4. The previously published version is archived atomically. Existing callers may keep a
   version number/checksum to bind an in-flight conversation to its original definition.

Run `docs/database/migrations/20260813_add_workflow_versions.sql` before enabling the API.
All management endpoints require the gateway-authenticated headers `X-User-Id` and
`X-User-Role: ROLE_ADMIN`.

## API

- `POST /assistant/api/admin/workflows/{key}/drafts`
- `POST /assistant/api/admin/workflows/validate`
- `POST /assistant/api/admin/workflows/{key}/versions/{version}/publish`
- `GET /assistant/api/admin/workflows/{key}/versions`
- `GET /assistant/api/admin/workflows/{key}/versions/{version}`

## Schema version 1 example

```json
{
  "schemaVersion": 1,
  "name": "product-order",
  "description": "Query products and create an order after approval",
  "maxGraphIterations": 4,
  "labels": { "domain": "commerce" },
  "nodes": [
    {
      "id": "query_product",
      "type": "AGENT",
      "description": "Query matching products",
      "targetAgent": "product",
      "operation": "QUERY",
      "input": {},
      "dependsOn": [],
      "conditions": [],
      "successCriteria": "A non-empty product list",
      "humanApprovalRequired": false,
      "constraints": ["Only expose products visible to the authenticated user"],
      "idempotencyKey": null
    },
    {
      "id": "create_order",
      "type": "HUMAN_APPROVAL",
      "description": "Create an order from the selected product",
      "targetAgent": "order",
      "operation": "CREATE_ORDER",
      "input": {},
      "dependsOn": ["query_product"],
      "conditions": [],
      "successCriteria": "An order id is returned",
      "humanApprovalRequired": true,
      "constraints": [],
      "idempotencyKey": "order-${requestId}"
    }
  ]
}
```

## Publication checks

Publication rejects unsupported schema versions, invalid or duplicate node IDs, missing
dependencies, ordinary dependency cycles, unknown agents, write nodes without idempotency,
approval nodes without approval, unsafe local/private URLs, and conditional reroutes without
a positive iteration budget. Only the governed `AGENT` and `HUMAN_APPROVAL` node types are
supported; arbitrary scripts and direct HTTP workflow nodes are deliberately excluded.
