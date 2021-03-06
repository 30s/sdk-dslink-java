package org.dsa.iot.broker.processor.stream;

import org.dsa.iot.broker.processor.Responder;
import org.dsa.iot.broker.server.client.Client;
import org.dsa.iot.broker.utils.ParsedPath;
import org.dsa.iot.dslink.methods.StreamState;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;

/**
 * @author Samuel Grenier
 */
public class GenericStream extends Stream {

    private Client requester;
    private int rid;

    public GenericStream(Responder responder, ParsedPath path) {
        super(responder, path);
    }

    @Override
    public void add(Client requester, int requesterRid) {
        if (this.requester != null) {
            throw new IllegalStateException("requester already set on stream");
        }
        this.requester = requester;
        this.rid = requesterRid;
    }

    @Override
    public void remove(Client requester) {
        this.requester = null;
    }

    @Override
    public boolean isEmpty() {
        return requester == null;
    }

    @Override
    public void dispatch(StreamState state, JsonObject response) {
        if (isEmpty()) {
            return;
        }

        response.put("rid", rid);
        JsonArray resps = new JsonArray();
        resps.add(response);
        requester.writeResponse(resps);

        if (state == StreamState.CLOSED) {
            close();
        }
    }

    @Override
    public void responderConnected() {
    }

    @Override
    public void responderDisconnected() {
        JsonObject resp = new JsonObject();
        resp.put("rid", rid);
        resp.put("stream", StreamState.CLOSED);
        JsonArray resps = new JsonArray();
        resps.add(resp);
        requester.writeResponse(resps);
        close();
    }

    private void close() {
        requester.processor().requester().removeStream(rid);
        close(requester, false);
        requester = null;
    }
}
