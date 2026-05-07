package com.example.demoscalardl.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.dl.ledger.contract.JacksonBasedContract;
import com.scalar.dl.ledger.exception.ContractContextException;
import com.scalar.dl.ledger.statemachine.Asset;
import com.scalar.dl.ledger.statemachine.Ledger;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled v1.0.2 of PutAssets, customised on top of v1.0.1.
 *
 * <p>Base name: PutAssets, version: 1.0.2.
 *
 * <p><b>Behavioural delta from v1.0.1:</b> the persisted {@code data} of every entry now carries
 * an {@code argument} field that snapshots the <b>full top-level</b> {@code argument} (i.e. the
 * complete {@code {assets: [...]}} batch) verbatim. The multi-asset RMW semantics and the
 * {@code sum of deltas == 0} conservation precondition are unchanged.
 *
 * <p>Persisted per-entry {@code data} shape becomes:
 * <pre>
 *   {
 *     "location":  "...",
 *     "item":      "...",
 *     "qty":       &lt;post-modify absolute&gt;,
 *     "argument":  { "assets": [ ...full original batch, copied verbatim... ] }
 *   }
 * </pre>
 *
 * <p>This makes any single Asset history sufficient to reconstruct "which batch transferred what",
 * at the cost of O(N) extra payload per entry (the same batch is replicated to all N entries).
 * Intended for small-to-medium batches (N up to ~10); for larger batches consider per-entry-only
 * argument snapshots in a future v1.0.3.
 *
 * <p>The per-row post-modify totals are published to the linked Function via
 * {@code setContext({"assets": [{"location","item","newQty"}, ...]})} in the same shape as v1.0.1,
 * so this Contract pairs cleanly with {@link com.example.demoscalardl.functions.PutAssetsFunctionV1_0_1}
 * (no new Function needed).
 *
 * <p><b>API compatibility note:</b> input shape is the same as v1.0.1, but the Ledger {@code data}
 * shape gains the {@code argument} field. Mixing v1.0.1 and v1.0.2 calls against the same asset
 * family produces a history where some entries carry {@code data.argument} and others do not.
 */
public class PutAssetsV1_0_2 extends JacksonBasedContract {

    @Override
    public JsonNode invoke(Ledger<JsonNode> ledger, JsonNode argument, JsonNode properties) {

        if (!argument.has("assets") || !argument.get("assets").isArray()) {
            throw new ContractContextException("argument.assets must be a non-empty array");
        }
        ArrayNode inputs = (ArrayNode) argument.get("assets");
        if (inputs.isEmpty()) {
            throw new ContractContextException("argument.assets must be a non-empty array");
        }

        for (JsonNode in : inputs) {
            if (!in.has("location")) {
                throw new ContractContextException("missing required field in assets[]: location");
            }
            if (!in.has("item")) {
                throw new ContractContextException("missing required field in assets[]: item");
            }
            if (!in.has("qty")) {
                throw new ContractContextException("missing required field in assets[]: qty");
            }
        }

        int sumOfDeltas = 0;
        for (JsonNode in : inputs) {
            sumOfDeltas += in.get("qty").asInt();
        }
        if (sumOfDeltas != 0) {
            throw new ContractContextException(
                    "sum of deltas across assets must equal 0 (got " + sumOfDeltas + ")");
        }

        List<String> assetIds = new ArrayList<>(inputs.size());
        List<Asset<JsonNode>> currents = new ArrayList<>(inputs.size());
        List<Integer> currentQtys = new ArrayList<>(inputs.size());
        List<Integer> deltas = new ArrayList<>(inputs.size());
        List<Integer> newQtys = new ArrayList<>(inputs.size());

        for (JsonNode in : inputs) {
            String assetId = in.get("location").asText() + ":" + in.get("item").asText();
            assetIds.add(assetId);

            Asset<JsonNode> existing = ledger.get(assetId).orElse(null);
            currents.add(existing);

            int currentQty = (existing != null && existing.data().has("qty"))
                    ? existing.data().get("qty").asInt() : 0;
            int delta = in.get("qty").asInt();
            int newQty = currentQty + delta;

            currentQtys.add(currentQty);
            deltas.add(delta);
            newQtys.add(newQty);
        }

        JsonNode topLevelArgumentSnapshot = argument.deepCopy();

        ArrayNode contextAssets = getObjectMapper().createArrayNode();
        for (int i = 0; i < inputs.size(); i++) {
            JsonNode in = inputs.get(i);
            String assetId = assetIds.get(i);
            int newQty = newQtys.get(i);

            ObjectNode merged = getObjectMapper().createObjectNode()
                    .put("location", in.get("location").asText())
                    .put("item", in.get("item").asText())
                    .put("qty", newQty);
            merged.set("argument", topLevelArgumentSnapshot);
            ledger.put(assetId, merged);

            contextAssets.add(getObjectMapper().createObjectNode()
                    .put("location", in.get("location").asText())
                    .put("item", in.get("item").asText())
                    .put("newQty", newQty));
        }

        setContext(getObjectMapper().createObjectNode().set("assets", contextAssets));

        ArrayNode summary = getObjectMapper().createArrayNode();
        for (int i = 0; i < inputs.size(); i++) {
            Asset<JsonNode> prior = currents.get(i);
            summary.add(getObjectMapper().createObjectNode()
                    .put("assetId", assetIds.get(i))
                    .put("status", prior == null ? "created" : "modified")
                    .put("previousAge", prior == null ? -1 : prior.age())
                    .put("previousQty", currentQtys.get(i))
                    .put("delta", deltas.get(i))
                    .put("newQty", newQtys.get(i)));
        }
        return getObjectMapper().createObjectNode()
                .put("count", inputs.size())
                .set("assets", summary);
    }
}
