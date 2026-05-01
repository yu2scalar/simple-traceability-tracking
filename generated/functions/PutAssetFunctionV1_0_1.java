package com.example.demoscalardl.functions;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Hand-rolled v1.0.1 of PutAssetFunction (paired with PutAssetV1_0_1).
 *
 * <p>Base name: PutAssetFunction, version: 1.0.1.
 *
 * <p><b>Behavioural delta from v1.0.0:</b> v1.0.0 writes {@code contractArgument.qty} (an absolute
 * value) directly into ScalarDB. v1.0.1 instead consumes {@code newQty} from
 * {@code getContractContext()} — the linked Contract has summed {@code currentQty + delta} and
 * publishes the result via {@code setContext({"newQty": ...})}. Writing the context value (not the
 * raw input delta) keeps the ScalarDB row aligned with the Ledger asset's stored quantity.
 *
 * <p>Asset-id keys ({@code location}, {@code item}) still come from {@code contractArgument} —
 * they identify which row to write and don't depend on the modify computation.
 */
public class PutAssetFunctionV1_0_1 extends JacksonBasedFunction {

    private static final String NAMESPACE = "ns_postgres";
    private static final String TABLE = "inventory";

    @Override
    public JsonNode invoke(
            Database<Get, Scan, Put, Delete, Result> database,
            JsonNode functionArgument,
            JsonNode contractArgument,
            JsonNode contractProperties) {

        if (!contractArgument.has("location")) {
            throw new ContractContextException("missing required field: location");
        }
        if (!contractArgument.has("item")) {
            throw new ContractContextException("missing required field: item");
        }

        JsonNode ctx = getContractContext();
        if (ctx == null || !ctx.has("newQty")) {
            throw new ContractContextException(
                    "expected linked Contract to setContext({\"newQty\": ...})");
        }
        int newQty = ctx.get("newQty").asInt();

        Key partitionKey = Key.newBuilder()
                .addText("location", contractArgument.get("location").asText())
                .build();

        Key clusteringKey = Key.newBuilder()
                .addText("item", contractArgument.get("item").asText())
                .build();

        Put put = Put.newBuilder()
                .namespace(NAMESPACE)
                .table(TABLE)
                .partitionKey(partitionKey)
                .clusteringKey(clusteringKey)
                .intValue("qty", newQty)
                .build();

        database.put(put);

        return getObjectMapper().createObjectNode()
                .put("status", "upserted")
                .put("table", NAMESPACE + "." + TABLE)
                .put("qty", newQty);
    }
}
