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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.node.cli.shell.Shell;
import io.bosonnetwork.node.cli.shell.ShellSession;

/**
 * Tests of the shell's own behaviour: how a line is split, what it does with what it cannot run, and
 * how it reports a failure. The commands themselves need a node, which these do not start.
 */
class ShellTests {
	@Test
	void unquotedWhitespaceSeparatesArguments() {
		assertEquals(List.of("find", "value", "-m", "optimistic", "abc"),
				Shell.tokenize("  find value\t-m  optimistic abc  "));
	}

	@Test
	void aBlankLineHasNoArguments() {
		assertEquals(List.of(), Shell.tokenize(""));
		assertEquals(List.of(), Shell.tokenize(" \t "));
	}

	@Test
	void quotesKeepWhitespaceInOneArgument() {
		assertEquals(List.of("store", "value", "hello from the shell"),
				Shell.tokenize("store value \"hello from the shell\""));
		assertEquals(List.of("store", "value", "hello from the shell"),
				Shell.tokenize("store value 'hello from the shell'"));
	}

	@Test
	void singleQuotesCarryJsonUnchanged() {
		assertEquals(List.of("announce", "peer", "-e", "{\"a\": \"b\\\\c\"}", "tcp://x"),
				Shell.tokenize("announce peer -e '{\"a\": \"b\\\\c\"}' tcp://x"));
	}

	@Test
	void doubleQuotesUnescapeOnlyQuoteAndBackslash() {
		assertEquals(List.of("say \"hi\" \\ \\n"), Shell.tokenize("\"say \\\"hi\\\" \\\\ \\n\""));
	}

	@Test
	void aBackslashOutsideQuotesIsOrdinary() {
		assertEquals(List.of("cache", "C:\\Users\\boson\\data"), Shell.tokenize("cache C:\\Users\\boson\\data"));
	}

	@Test
	void adjacentPartsFormOneArgumentAndEmptyQuotesAreAnArgument() {
		assertEquals(List.of("abc d", ""), Shell.tokenize("a'bc'\" d\" \"\""));
	}

	@Test
	void anUnterminatedQuoteIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Shell.tokenize("store value \"hello"));
		assertThrows(IllegalArgumentException.class, () -> Shell.tokenize("store value 'hello"));
	}

	private static String session(String input) throws Exception {
		StringWriter text = new StringWriter();
		PrintWriter writer = new PrintWriter(text, true);
		ShellSession session = new ShellSession(null, new Output(writer, writer, false), null);
		assertEquals(0, new Shell(session, new StringReader(input), writer).run());
		return text.toString();
	}

	@Test
	void theShellEndsAtTheEndOfInput() throws Exception {
		assertEquals(Shell.PROMPT + Shell.PROMPT + System.lineSeparator(), session("\n"));
	}

	@Test
	void exitAndQuitEndTheShellWithoutReadingFurther() throws Exception {
		assertEquals(Shell.PROMPT, session("exit\nbogus\n"));
		assertEquals(Shell.PROMPT, session("  quit  \nbogus\n"));
	}

	@Test
	void anUnknownCommandIsReportedAndTheShellContinues() throws Exception {
		String output = session("bogus arg\nexit\n");
		assertTrue(output.contains("Unknown command 'bogus'"), output);
		assertTrue(output.endsWith(Shell.PROMPT), output);
	}

	@Test
	void anUnterminatedQuoteIsReportedAndTheShellContinues() throws Exception {
		String output = session("store value \"oops\nexit\n");
		assertTrue(output.contains("Error: Unterminated double quote"), output);
		assertFalse(output.contains("Unknown command"), output);
	}

	@Test
	void helpListsTheCommandsAndHowToLeave() throws Exception {
		String output = session("help\nexit\n");
		for (String command : List.of("id", "bootstrap", "find", "store", "announce", "routing", "storage", "cache",
				"keygen", "stop"))
			assertTrue(output.contains(command), command + " missing from:\n" + output);
		assertTrue(output.contains("Type 'exit' or 'quit'"), output);
		assertTrue(output.contains("Usage: COMMAND"), output);
	}

	@Test
	void aCommandsOwnHelpIsReachable() throws Exception {
		assertTrue(session("find value --help\nexit\n").contains("Usage: find value"));
		assertTrue(session("storage values --help\nexit\n").contains("Usage: storage values"));
		assertTrue(session("store value --help\nexit\n").contains("Usage: store value"));
		assertTrue(session("id -h\nexit\n").contains("Usage: id"));
		assertTrue(session("find -h\nexit\n").contains("Usage: find"));
	}

	@Test
	void shellCommandsTakeNoVersionOption() throws Exception {
		// --version is the tool's, outside the shell; a command typed at the prompt only offers help.
		String output = session("id --version\nexit\n");
		assertTrue(output.contains("Unknown option: '--version'"), output);
		assertFalse(session("help id\nexit\n").contains("--version"));
	}

	@Test
	void aGroupWithoutACommandListsIt() throws Exception {
		String output = session("find\nexit\n");
		assertTrue(output.contains("needs one of the commands"), output);
		assertTrue(output.contains("value"), output);
	}

	@Test
	void aParameterErrorIsReportedAndTheShellContinues() throws Exception {
		String output = session("find value\nexit\n");
		assertTrue(output.contains("Missing required parameter"), output);
		assertTrue(output.endsWith(Shell.PROMPT), output);
	}

	@Test
	void aCommandThatNeedsTheNodeSaysSoRatherThanThrowing() throws Exception {
		String output = session("id\nexit\n");
		assertTrue(output.contains("The node is not running"), output);
		assertFalse(output.contains("Exception"), output);
	}

	@Test
	void anIdArgumentIsReadRatherThanRefused() throws Exception {
		// The argument converts before the command runs: without a node it stops at the node, which is
		// as far as this can get without one. A missing converter would stop it at the argument.
		String output = session("find node " + io.bosonnetwork.Id.random() + "\nexit\n");
		assertTrue(output.contains("The node is not running"), output);
		assertFalse(output.contains("TypeConverter"), output);
	}

	@Test
	void anInvalidIdIsRefusedWithItsOwnMessage() throws Exception {
		String output = session("find node not-an-id\nexit\n");
		assertTrue(output.contains("not a valid id"), output);
	}

	@Test
	void aFailureIsDescribedByItsCause() {
		assertEquals("IllegalStateException: Node not running",
				Shell.describe(new ExecutionException(new CompletionException(new IllegalStateException("Node not running")))));
		assertEquals("NullPointerException", Shell.describe(new NullPointerException()));
	}
}
