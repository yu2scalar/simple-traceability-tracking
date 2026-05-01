package com.example.demoscalardl.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.dl.ledger.contract.JacksonBasedContract;
import com.scalar.dl.ledger.exception.ContractContextException;
import com.scalar.dl.ledger.statemachine.Asset;
import com.scalar.dl.ledger.statemachine.Ledger;

import java.util.Optional;

/**
 * Hand-rolled v1.0.1 of PutAsset, customised on top of the skill-rendered v1.0.0.
 *
 * <p>Base name: PutAsset, version: 1.0.1.
 *
 * <p><b>Behavioural delta from v1.0.0:</b> {@code argument.qty} is now treated as a
 * <em>delta</em> instead of an absolute value. The Contract reads any prior asset, sums
 * {@code currentQty + delta}, and writes the resulting state back. If no prior asset exists,
 * {@code currentQty} is treated as 0 (so the first call writes {@code qty = delta}).
 *
 * <p>The post-modify {@code newQty} is published to the linked Function via
 * {@code setContext({"newQty": ...})} so the paired Function can write the same value into
 * ScalarDB. Pair this version with {@link com.example.demoscalardl.functions.PutAssetFunctionV1_0_1}.
 *
 * <p><b>API compatibility note:</b> v1.0.0 and v1.0.1 are NOT wire-compatible. v1.0.0 takes
 * {@code qty} as an absolute write; v1.0.1 takes it as a delta. Clients must pick which version
 * to call by {@code contractId}. Both versions can coexist on the Ledger; once registered, neither
 * can be replaced or disabled.
 */
public class PutAssetV1_0_1 extends JacksonBasedContract {

    /**
     * @param ledger     tamper-evident asset Ledger.
     * @param argument   signed JSON: {@code {location, item, qty}}. {@code qty} is the initial
     *                   value on first write, or a delta on subsequent writes.
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
