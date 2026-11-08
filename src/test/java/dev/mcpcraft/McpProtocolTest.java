package dev.mcpcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpcraft.mcp.McpProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON-RPC layer: parsing, errors, batches. */
class McpProtocolTest {

	private static JsonObject rpc(String method, JsonObject params, int id) {
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", id);
		req.addProperty("method", method);
		if (params != null) {
			req.add("params", params);
		}
		return req;
	}

	@Test
	void initializeReturnsProtocolVersionAndServerInfo() {
		JsonObject response = McpProtocol.handle(rpc("initialize", new JsonObject(), 1));
		assertNotNull(response);
		JsonObject result = response.getAsJsonObject("result");
		assertEquals(McpProtocol.PROTOCOL_VERSION, result.get("protocolVersion").getAsString());
		assertEquals(McpProtocol.SERVER_NAME, result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
	}

	@Test
	void initializedNotificationProducesNoResponse() {
		JsonObject notification = rpc("notifications/initialized", null, 1);
		notification.remove("id");
		assertNull(McpProtocol.handle(notification));
	}

	@Test
	void pingReturnsEmptyResult() {
		JsonObject response = McpProtocol.handle(rpc("ping", null, 5));
		assertNotNull(response);
		assertEquals(5, response.get("id").getAsInt());
		assertTrue(response.getAsJsonObject("result").isEmpty());
	}

	@Test
	void toolsListIsArray() {
		JsonObject response = McpProtocol.handle(rpc("tools/list", null, 2));
		assertNotNull(response);
		JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
		assertNotNull(tools);
	}

	@Test
	void unknownMethodReturnsMethodNotFound() {
		JsonObject response = McpProtocol.handle(rpc("no/such/method", null, 3));
		assertNotNull(response);
		assertEquals(McpProtocol.METHOD_NOT_FOUND, response.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void unknownToolReturnsError() {
		JsonObject params = new JsonObject();
		params.addProperty("name", "mc_does_not_exist");
		params.add("arguments", new JsonObject());

		JsonObject response = McpProtocol.handle(rpc("tools/call", params, 4));
		assertNotNull(response);
		assertEquals(McpProtocol.TOOL_FAILED, response.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void resourcesAndPromptsReturnEmptyLists() {
		for (String[] pair : new String[][]{
				{"resources/list", "resources"},
				{"resources/templates/list", "resourceTemplates"},
				{"prompts/list", "prompts"}}) {
			JsonObject response = McpProtocol.handle(rpc(pair[0], null, 10));
			assertNotNull(response, pair[0]);
			assertTrue(response.getAsJsonObject("result").getAsJsonArray(pair[1]).isEmpty(), pair[0]);
		}
	}

	@Test
	void invalidRequestWithoutMethodReturnsError() {
		JsonObject bad = new JsonObject();
		bad.addProperty("jsonrpc", "2.0");
		bad.addProperty("id", 9);
		JsonObject response = McpProtocol.handle(bad);
		assertNotNull(response);
		assertEquals(McpProtocol.INVALID_REQUEST, response.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void errorResponseShapeIsValid() {
		JsonObject err = McpProtocol.error(JsonParser.parseString("42"), -32601, "no such method");
		assertEquals("2.0", err.get("jsonrpc").getAsString());
		assertEquals(42, err.get("id").getAsInt());
		assertEquals(-32601, err.getAsJsonObject("error").get("code").getAsInt());
		assertEquals("no such method", err.getAsJsonObject("error").get("message").getAsString());
	}
}
