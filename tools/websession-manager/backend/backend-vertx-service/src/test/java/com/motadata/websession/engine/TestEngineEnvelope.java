package com.motadata.websession.engine;

import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEngineEnvelope
{

    @Test
    void testCreateAndSerializeEnvelope()
    {
        JsonObject payload = new JsonObject()
            .put("host", "192.168.1.100")
            .put("port", 22);

        EngineEnvelope env = EngineEnvelope.create("open", "session-xyz", 1, payload);

        Assertions.assertEquals(1, env.getVersion());
        Assertions.assertEquals("open", env.getType());
        Assertions.assertEquals("session-xyz", env.getSessionId());
        Assertions.assertEquals(1, env.getSeq());
        Assertions.assertNotNull(env.getTs());
        Assertions.assertEquals(payload, env.getPayload());

        JsonObject json = env.toJson();
        Assertions.assertEquals(1, json.getInteger("v"));
        Assertions.assertEquals("open", json.getString("type"));
        Assertions.assertEquals("session-xyz", json.getString("session.id"));
        Assertions.assertEquals("session-xyz", json.getString("sessionId"));
        Assertions.assertEquals(1, json.getLong("seq"));
    }

    @Test
    void testDeserializeEnvelopeWithBothSessionIdKeys()
    {
        // Motadata style dot-notation "session.id"
        JsonObject json1 = new JsonObject()
            .put("v", 1)
            .put("type", "state")
            .put("session.id", "s-1")
            .put("seq", 5)
            .put("payload", new JsonObject().put("state", "connected"));

        EngineEnvelope env1 = EngineEnvelope.fromJson(json1);
        Assertions.assertEquals("s-1", env1.getSessionId());
        Assertions.assertEquals("connected", env1.getPayload().getString("state"));

        // CamelCase "sessionId"
        JsonObject json2 = new JsonObject()
            .put("v", 1)
            .put("type", "state")
            .put("sessionId", "s-2")
            .put("seq", 6);

        EngineEnvelope env2 = EngineEnvelope.fromJson(json2);
        Assertions.assertEquals("s-2", env2.getSessionId());
    }
}
