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
 * Hand-rolled v1.0.1 of PutAssets, customised on top of the skill-rendered v1.0.0.
 *
 * <p>Base name: PutAssets, version: 1.0.1.
 *
 * <p><b>Behavioural delta from v1.0.0:</b> each {@code assets[i].qty} is treated as a
 * <em>delta</em> instead of an absolute value. For every entry, the Contract reads the prior
 * asset (if any), computes {@code newQty = (currentQty or 0) + delta}, and writes the resulting
 * state back. All assets are written atomically in the same Ledger transaction.
 *
 * <p><b>Conservation precondition:</b> the sum of all {@code assets[i].qty} deltas must equal 0.
 * Violations throw {@code ContractContextException} with message
 * {@code "sum of deltas across assets must equal 0 (got <n>)"}. The check runs <em>before</em> any
 * {@code ledger.get}, so unbalanced inputs fail fast without consuming Snapshot reads.
 *
 * <p>The per-row post-modify totals are published to the linked Function via
 * {@code setContext({"assets": [{"location","item","newQty"}, ...]})} so the paired Function can
 * write each value into ScalarDB. Pair this version with
 * {@link com.example.demoscalardl.functions.PutAssetsFunctionV1_0_1}.
 *
 * <p><b>API compatibility note:</b> v1.0.0 and v1.0.1 are NOT wire-compatible. v1.0.0 takes each
 * {@code qty} as an absolute write; v1.0.1 takes it as a delta plus enforces the conservation rule.
 */
public class PutAssetsV1_0_1 extends JacksonBasedContract {

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

        ArrayNode contextAssets = getObjectMapper().createArrayNode();
        for (int i = 0; i < inputs.size(); i++) {
            JsonNode in = inputs.get(i);
            String assetId = assetIds.get(i);
            int newQty = newQtys.get(i);

            ObjectNode merged = getObjectMapper().createObjectNode()
                    .put("location", in.get("location").asText())
                    .put("item", in.get("item").asText())
                    .put("qty", newQty);
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
