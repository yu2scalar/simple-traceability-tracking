package com.example.demoscalardl.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scalar.db.api.Delete;
import com.scalar.db.api.Get;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.io.Key;
import com.scalar.dl.ledger.database.Database;
import com.scalar.dl.ledger.exception.ContractContextException;
import com.scalar.dl.ledger.function.JacksonBasedFunction;

/**
 * Hand-rolled v1.0.1 of PutAssetsFunction, paired with {@code PutAssetsV1_0_1}.
 *
 * <p>Base name: PutAssetsFunction, version: 1.0.1.
 *
 * <p><b>Behavioural delta from v1.0.0:</b> v1.0.0 iterates {@code contractArgument.assets[]} and
 * writes each entry's raw {@code qty} (a delta) into ScalarDB — which, for the RMW Contract, is
 * wrong (the row would land at the delta value, not the post-modify total). v1.0.1 instead reads
 * the per-row {@code newQty} that the linked Contract published via
 * {@code setContext({"assets": [{"location","item","newQty"}, ...]})} and writes those values.
 *
 * <p>Partition / clustering keys come from the per-entry context (not from
 * {@code contractArgument}), because the Contract has already validated and computed each entry's
 * identity. This keeps the Function loop self-contained and resilient to future Contract changes
 * that might filter out entries (e.g. skipping no-op deltas).
 */
public class PutAssetsFunctionV1_0_1 extends JacksonBasedFunction {

    private static final String NAMESPACE = "ns_postgres";
    private static final String TABLE = "inventory";

    @Override
    public JsonNode invoke(
            Database<Get, Scan, Put, Delete, Result> database,
            JsonNode functionArgument,
            JsonNode contractArgument,
            JsonNode contractProperties) {

        JsonNode ctx = getContractContext();
        if (ctx == null || !ctx.has("assets") || !ctx.get("assets").isArray()) {
            throw new ContractContextException(
                    "expected linked Contract to setContext({\"assets\": [{location, item, newQty}, ...]})");
        }
        ArrayNode assets = (ArrayNode) ctx.get("assets");
        if (assets.isEmpty()) {
            throw new ContractContextException("contract context assets array is empty");
        }

        for (JsonNode entry : assets) {
            if (!entry.has("location") || !entry.has("item") || !entry.has("newQty")) {
                throw new ContractContextException(
                        "context asset entry missing required fields: location, item, newQty");
            }

            Key partitionKey = Key.newBuilder()
                    .addText("location", entry.get("location").asText())
                    .build();

            Key clusteringKey = Key.newBuilder()
                    .addText("item", entry.get("item").asText())
                    .build();

            Put put = Put.newBuilder()
                    .namespace(NAMESPACE)
                    .table(TABLE)
                    .partitionKey(partitionKey)
                    .clusteringKey(clusteringKey)
                    .intValue("qty", entry.get("newQty").asInt())
                    .build();

            database.put(put);
        }

        return getObjectMapper().createObjectNode()
                .put("status", "upserted")
                .put("count", assets.size())
                .put("table", NAMESPACE + "." + TABLE);
    }
}
