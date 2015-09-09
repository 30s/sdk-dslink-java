package org.dsa.iot.dslink.methods.responses;

import org.dsa.iot.dslink.link.Requester;
import org.dsa.iot.dslink.methods.Response;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.node.value.SubscriptionValue;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.node.value.ValueUtils;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.util.Map;

/**
 * @author Samuel Grenier
 */
public class SubscriptionUpdate implements Response {

    private final Requester requester;
    private final NodeManager manager;

    public SubscriptionUpdate(Requester requester) {
        this.requester = requester;
        this.manager = requester.getDSLink().getNodeManager();
    }

    @Override
    public int getRid() {
        return 0;
    }

    @Override
    public void populate(JsonObject in) {
        JsonArray updates = in.getArray("updates");
        Map<Integer, String> paths = requester.getSubscriptionIDs();
        Map<Integer, Handler<SubscriptionValue>> handlers = requester.getSubscriptionHandlers();
        if (updates != null) {
            for (Object obj : updates) {
                int rid;
                String path;
                Object valueObj;
                String timestamp;
                Integer count = null;
                Integer sum = null;
                Integer min = null;
                Integer max = null;

                if (obj instanceof JsonArray) {
                    JsonArray update = (JsonArray) obj;
                    rid = update.get(0);
                    path = paths.get(rid);
                    valueObj = update.get(1);
                    timestamp = update.get(2);
                } else if (obj instanceof JsonObject) {
                    JsonObject update = (JsonObject) obj;
                    rid = update.getInteger("sid");
                    path = paths.get(rid);
                    valueObj = update.getField("value");
                    timestamp = update.getString("ts");
                    count = update.getInteger("count");
                    sum = update.getInteger("sum");
                    min = update.getInteger("min");
                    max = update.getInteger("max");
                } else {
                    String err = "Invalid subscription update: " + in.encode();
                    throw new RuntimeException(err);
                }
                if (path == null) {
                    continue;
                }

                final Node node = manager.getNode(path, true).getNode();
                Value val = ValueUtils.toValue(valueObj, timestamp);
                if (val == null) {
                    ValueType type = node.getValueType();
                    if (type != null) {
                        val = ValueUtils.toEmptyValue(type, timestamp);
                    } else {
                        continue;
                    }
                }

                node.setValueType(val.getType());
                node.setValue(val);

                Handler<SubscriptionValue> handler = handlers.get(rid);
                SubscriptionValue value;
                if (handler != null) {
                    value = new SubscriptionValue(path, val, count, sum, min, max);
                    handler.handle(value);
                }
            }
        }
    }

    @Override
    public JsonObject getJsonResponse(JsonObject in) {
        return null;
    }

    @Override
    public JsonObject getCloseResponse() {
        return null;
    }
}
