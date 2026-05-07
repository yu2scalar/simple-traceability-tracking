package com.example.demoscalardl.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.dl.ledger.contract.JacksonBasedContract;
import com.scalar.dl.ledger.exception.ContractContextException;
import com.scalar.dl.ledger.statemachine.Asset;
import com.scalar.dl.ledger.statemachine.Ledger;

import java.util.Optional;

/**
 * Hand-rolled v1.0.2 of PutAsset, customised on top of v1.0.1.
 *
 * <p>Base name: PutAsset, version: 1.0.2.
 *
 * <p><b>Behavioural delta from v1.0.1:</b> the persisted {@code data} now carries an
 * {@code argument} field that snapshots the original execute-time {@code argument} verbatim. The
 * RMW semantics for {@code qty} (delta-applied) are unchanged.
 *
 * <p>Persisted {@code data} shape becomes:
 * <pre>
 *   {
 *     "location":  "...",
 *     "item":      "...",
 *     "qty":       &lt;post-modify absolute&gt;,
 *     "argument":  { ... original argument JSON, copied verbatim ... }
 *   }
 * </pre>
 *
 * <p>This makes the original delta (and any future fields the caller passed) recoverable through
 * {@code ReadAssetV1_0_0} / {@code ledger.scan} on a per-entry basis, restoring traceability that
 * was previously locked inside the Ledger's internal argument table.
 *
 * <p>The post-modify {@code newQty} is published to the linked Function via
 * {@code setContext({"newQty": ...})} in the same shape as v1.0.1, so this Contract pairs cleanly
 * with {@link com.example.demoscalardl.functions.PutAssetFunctionV1_0_1} (no new Function needed).
 *
 * <p><b>API compatibility note:</b> input shape is the same as v1.0.1, but the Ledger {@code data}
 * shape gains the {@code argument} field. Mixing v1.0.1 and v1.0.2 calls against the same
 * {@code assetId} produces a history where some entries carry {@code data.argument} and others do
 * not. In production, pin one version per asset family.
 */
public class PutAssetV1_0_2 extends JacksonBasedContract {

    /**
     * @param ledger     tamper-evident asset Ledger.
     * @param argument   signed JSON: {@code {location, item, qty}}. {@code qty} is the initial
     *                   value on first write, or a delta on subsequent writes. The full argument
     *                   is also persisted verbatim under {@code data.argument} for traceability.
     * @param properties JSON registered with this Contract at register time, or {@code null}.
     * @return JSON {@code {status, assetId, [previousAge, previousQty, delta,] newQty}}.
     */
    @Override
    public JsonNode invoke(Ledger<JsonNode> ledger, JsonNode argument, JsonNode properties) {

        if (!argument.has("location")) {
            throw new ContractContextException("missing required field: location");
        }
        if (!argument.has("item")) {
            throw new ContractContextException("missing required field: item");
        }
        if (!argument.has("qty")) {
            throw new ContractContextException("missing required field: qty");
        }

        String assetId = argument.get("location").asText() + ":" + argument.get("item").asText();
        Optional<Asset<JsonNode>> existing = ledger.get(assetId);

        int delta = argument.get("qty").asInt();

        if (!existing.isPresent()) {
            ObjectNode initial = getObjectMapper().createObjectNode()
                    .put("location", argument.get("location").asText())
                    .put("item", argument.get("item").asText())
                    .put("qty", delta);
            initial.set("argument", argument.deepCopy());
            ledger.put(assetId, initial);

            setContext(getObjectMapper().createObjectNode().put("newQty", delta));

            return getObjectMapper().createObjectNode()
                    .put("status", "created")
                    .put("assetId", assetId)
                    .put("newQty", delta);
        }

        JsonNode current = existing.get().data();

        int currentQty = current.has("qty") ? current.get("qty").asInt() : 0;
        int newQty = currentQty + delta;

        ObjectNode merged = getObjectMapper().createObjectNode()
                .put("location", argument.get("location").asText())
                .put("item", argument.get("item").asText())
                .put("qty", newQty);
        merged.set("argument", argument.deepCopy());

        ledger.put(assetId, merged);

        setContext(getObjectMapper().createObjectNode().put("newQty", newQty));

        return getObjectMapper().createObjectNode()
                .put("status", "modified")
                .put("assetId", assetId)
                .put("previousAge", existing.get().age())
                .put("previousQty", currentQty)
                .put("delta", delta)
                .put("newQty", newQty);
    }
}
