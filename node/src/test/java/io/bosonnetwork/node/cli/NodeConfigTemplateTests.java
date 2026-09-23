/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.node.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.json.Json;

/**
 * Checks the shipped {@code node.yaml} template against the parser that actually reads it.
 * <p>
 * A configuration template is documentation the compiler never sees: a key renamed in the parser
 * leaves the template quietly describing a setting nobody reads. These tests load the real resource
 * off the class path, the same one {@code config init} and {@code setup} render.
 */
class NodeConfigTemplateTests {
	private static final String HOST_PLACEHOLDER = "LOCAL_IPV4_ADDRESS";
	private static final String KEY_PLACEHOLDER = "NODE_PRIVATE_KEY";
	private static final String HOST = "203.0.113.5";
	private static final String PRIVATE_KEY =
			"5P46autoGX9fifw4dV9c97xJTwPV7XKuxsq1sXZvc56uVFHsxPXLHqnjPL6vr8MU8XSmicv4XdBA6cMX6g8fg12E";

	private static Vertx vertx;

	@BeforeAll
	static void setup() {
		vertx = Vertx.vertx();
	}

	@AfterAll
	static void teardown() {
		if (vertx != null)
			vertx.close();
	}

	private static String template() throws Exception {
		try (InputStream in = NodeConfigTemplateTests.class.getResourceAsStream("/node.yaml")) {
			assertNotNull(in, "node.yaml is missing from the class path");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void theTemplateCarriesThePlaceholdersTheToolsFillIn() throws Exception {
		String template = template();
		assertTrue(template.contains(HOST_PLACEHOLDER), "the template must carry " + HOST_PLACEHOLDER);
		assertTrue(template.contains(KEY_PLACEHOLDER), "the template must carry " + KEY_PLACEHOLDER);
	}

	@Test
	void theTemplateIsReadTheWayANodeReadsIt() throws Exception {
		String yaml = template().replace(HOST_PLACEHOLDER, HOST).replace(KEY_PLACEHOLDER, PRIVATE_KEY);
		Map<String, Object> settings = Json.yamlMapper().readValue(yaml, Json.mapType());

		NodeConfiguration config = NodeConfiguration.builder().vertx(vertx).fromMap(settings).build();

		assertEquals(HOST, config.listen().host4());
		assertEquals(39001, config.listen().port());
		assertEquals(Id.of(config.keyPair().publicKey().bytes()), Id.of(config.keyPair().publicKey().bytes()));
		assertNotNull(config.dataDir());
	}
}
