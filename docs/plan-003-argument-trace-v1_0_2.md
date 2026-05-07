# Plan 003: Argument Trace Contracts (V1_0_2) — port from demo-scalardl-skills-03

- Status: **Implemented 2026-05-07** — 2 hand-rolled `.java` ファイルを sibling project (`~/IdeaProjects/demo-scalardl-skills-03/demo-scalardl-app/generated/contracts/`) から byte-for-byte copy。`./gradlew compileJava` BUILD SUCCESSFUL (両方 major version 61)。
- Branch: `chore/namespace-to-demo` (継続)
- Source of truth: `~/IdeaProjects/demo-scalardl-skills-03/docs/plan-003-argument-trace-v1_0_2.md`
- Working project: this directory (`simple-traceability-tracking/`)

## 1. Goal

`demo-scalardl-skills-03` で実装した V1_0_2 argument trace 機能 (RMW + 元 argument を `data.argument` に nest して保存) を、本 standalone Gradle project にも port する。**Contract 2 ファイルを byte-for-byte コピー** で取り込む。

両 project は:
- 同じ `package com.example.demoscalardl.contracts`
- 同じ V1_0_0 / V1_0_1 Contract bytecode shape
- ScalarDL Java Client SDK 3.13.0 を使用

を共有しているため、Contract 側は何の調整も必要としない。

## 2. Sibling project との差分 (重要)

| 観点 | demo-scalardl-skills-03 | simple-traceability-tracking (this project) |
|---|---|---|
| プロジェクト形態 | Spring Boot + Mustache + 実行時 compile pipeline | Standalone Gradle (`./gradlew build`) |
| ScalarDB namespace | `ns_postgres` | **`demo`** (commit 1f8c921 で rename 済み) |
| V1_0_1 Function `NAMESPACE` 定数 | `"ns_postgres"` | `"demo"` |
| V1_0_2 Contract bytecode | 同じ | 同じ (Contract は ScalarDB namespace を参照しない) |
| docs/ | あり (plan-001, 002, 003, contracts-and-functions.md) | この plan-003 のみ |

**namespace 差は Function 側のみ**。V1_0_2 Contract は ScalarDB に直接書かないので影響なし。クライアントが `register-from-source` → `execute` する際は、本 project の Function (NAMESPACE="demo") をそのまま pair すればよい。

## 3. 実装サマリ (詳細は sibling project の plan-003 を参照)

V1_0_1 と V1_0_2 の唯一の差:

```java
// V1_0_1: ledger.put に渡す ObjectNode
ObjectNode merged = getObjectMapper().createObjectNode()
        .put("location", ...)
        .put("item", ...)
        .put("qty", newQty);
ledger.put(assetId, merged);

// V1_0_2: 1 行追加して original argument を nest
ObjectNode merged = ...
        .put("qty", newQty);
merged.set("argument", argument.deepCopy());   // ← V1_0_2 で追加
ledger.put(assetId, merged);
```

multi-asset (`PutAssetsV1_0_2`) では各 entry に **top-level 全体 (`{assets:[...]}`) を deepCopy 1 回作って共有**。

`setContext(...)` の shape は V1_0_1 と完全互換 → V1_0_1 Function (本 project の `NAMESPACE="demo"` 版) を再利用可能。

## 4. 確認事項 (sibling project Plan 003 から継承)

| # | 決定 |
|---|---|
| Q1 | スコープ: 新規 V1_0_2 として 2 Contract のみ追加 (V1_0_0/V1_0_1 untouched) |
| Q2 | data shape: `data.argument = {元 argument}` |
| Q3 | multi-asset: 各 entry に top-level 全体 (`{assets:[...]}`) を nest |
| Q4 | implementation: hand-rolled `.java` (テンプレート編集なし) |
| Q5 | Function 側: 不要 (V1_0_1 Function を再利用) |
| Q6 | Java release: 17 (`build.gradle` の `options.release.set(17)` で enforced) |

## 5. New files (2 total)

`generated/contracts/` 配下に追加:

- `PutAssetV1_0_2.java` (4810 bytes — sibling と byte-identical)
- `PutAssetsV1_0_2.java` (6218 bytes — sibling と byte-identical)

既存 9 ファイル (V1_0_0 / V1_0_1 Contract 4 + Function 4 + ReadAssetV1_0_0) は **untouched**。

## 6. Build / smoke-test

```bash
./gradlew compileJava   # BUILD SUCCESSFUL
ls build/classes/java/main/com/example/demoscalardl/contracts/
# PutAssetV1_0_2.class    (4375 bytes, major version 61)
# PutAssetsV1_0_2.class   (6968 bytes, major version 61)
```

`./gradlew jar` で deliverable jar (`build/libs/simple-traceability-tracking-1.0.0.jar`) も差分なくビルド可能 (本 plan の確認時点では未実行)。

## 7. Out of scope

- **No edits to V1_0_0 / V1_0_1** — 既存 9 ファイル不変
- **No new Function** — V1_0_1 Function (`NAMESPACE="demo"`) を再利用
- **No registration / execution against Ledger** — user 側で実施
- **No commit / push / PR** — user の指示待ち (本 session では作業のみ)

## 8. Cross-reference

実装の **意思決定理由 / 設計トレードオフ / curl recipe** は sibling project の以下を参照:

- `~/IdeaProjects/demo-scalardl-skills-03/docs/plan-003-argument-trace-v1_0_2.md` — 同設計の本家 plan
- `~/IdeaProjects/demo-scalardl-skills-03/docs/contracts-and-functions.md` §2.6, §2.7, §6.7 — V1_0_2 仕様 + curl 例 (本 project に当てはめる際は `ns_postgres` → `demo` の読替えのみ)

## 9. Verification audit (per CLAUDE.md rule)

- [x] `generated/contracts/PutAssetV1_0_2.java` 存在 (`ls`)
- [x] `generated/contracts/PutAssetsV1_0_2.java` 存在 (`ls`)
- [x] sibling project と byte-identical (`diff` exit 0)
- [x] `./gradlew compileJava` BUILD SUCCESSFUL
- [x] `.class` 出力で major version 61 (`javap -v`)
- [x] `docs/plan-003-argument-trace-v1_0_2.md` (this file) 作成
