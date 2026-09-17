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

package io.bosonnetwork.cli.common;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.bosonnetwork.json.Json;

/**
 * Writes what a command has to say.
 * <p>
 * Results go to standard output: tables, details and one-line messages for people, or JSON with
 * {@code --json}. Progress and warnings go to standard error, so that the results can be piped
 * without them. In JSON mode only the JSON is written to standard output.
 */
public final class Output {
	private static final String COLUMN_GAP = "  ";

	private final PrintWriter out;
	private final PrintWriter err;
	private final boolean json;

	/**
	 * Creates the output.
	 *
	 * @param out  standard output
	 * @param err  standard error
	 * @param json whether results are written as JSON
	 */
	public Output(PrintWriter out, PrintWriter err, boolean json) {
		this.out = out;
		this.err = err;
		this.json = json;
	}

	/**
	 * Tells whether results are written as JSON.
	 *
	 * @return {@code true} with {@code --json}
	 */
	public boolean isJson() {
		return json;
	}

	/**
	 * Writes a message for people, such as what a command did. Nothing in JSON mode.
	 *
	 * @param text the message
	 */
	public void message(String text) {
		if (!json)
			out.println(text);
	}

	/**
	 * Writes an empty line between parts of the output. Nothing in JSON mode.
	 */
	public void blank() {
		if (!json)
			out.println();
	}

	/**
	 * Tells the user what a slow command is doing, on standard error. Nothing in JSON mode.
	 *
	 * @param text the message
	 */
	public void progress(String text) {
		if (!json) {
			err.println(text);
			err.flush();
		}
	}

	/**
	 * Warns the user, on standard error, whatever the mode.
	 *
	 * @param text the warning
	 */
	public void warning(String text) {
		err.println("Warning: " + text);
		err.flush();
	}

	/**
	 * Writes a result as JSON: maps, lists, strings, numbers, booleans and Boson ids.
	 *
	 * @param value the result
	 */
	public void json(Object value) {
		try {
			out.println(Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
		} catch (JsonProcessingException e) {
			// The values are built by the commands from plain types; failing here is a bug.
			throw new IllegalStateException("Cannot write JSON output: " + e.getMessage(), e);
		}
	}

	/**
	 * Writes an event as JSON on one line, for a command that reports as it goes: a script reads one
	 * object per line. Standard output is flushed, so that a reader sees each event when it happens.
	 *
	 * @param value the event
	 */
	public void jsonLine(Object value) {
		try {
			out.println(Json.objectMapper().writeValueAsString(value));
			out.flush();
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON output: " + e.getMessage(), e);
		}
	}

	/**
	 * Writes a message for people, and flushes it at once: for a command that runs until it is
	 * stopped. Nothing in JSON mode.
	 *
	 * @param text the message
	 */
	public void event(String text) {
		if (!json) {
			out.println(text);
			out.flush();
		}
	}

	/**
	 * Writes labelled values, one per line, with the values aligned. A value spanning several lines is
	 * indented to stay aligned.
	 *
	 * @param rows the values, keyed by label, in order
	 */
	public void details(Map<String, String> rows) {
		int width = 0;
		for (String label : rows.keySet())
			width = Math.max(width, label.length() + 1);

		String indent = " ".repeat(width + 1);
		for (Map.Entry<String, String> row : rows.entrySet()) {
			String label = row.getKey() + ":";
			List<String> lines = row.getValue().lines().toList();
			if (lines.isEmpty())
				lines = List.of("");

			out.println(label + " ".repeat(width - label.length() + 1) + lines.get(0));
			for (int i = 1; i < lines.size(); i++)
				out.println(indent + lines.get(i));
		}
	}

	/**
	 * Writes a table: the headers, then one line per row, with the columns aligned.
	 *
	 * @param headers the column headers
	 * @param rows    the rows, each with one value per column
	 */
	public void table(List<String> headers, List<List<String>> rows) {
		int[] widths = new int[headers.size()];
		for (int i = 0; i < headers.size(); i++)
			widths[i] = headers.get(i).length();
		for (List<String> row : rows)
			for (int i = 0; i < row.size(); i++)
				widths[i] = Math.max(widths[i], row.get(i).length());

		printRow(headers, widths);
		for (List<String> row : rows)
			printRow(row, widths);
	}

	private void printRow(List<String> values, int[] widths) {
		StringBuilder line = new StringBuilder();
		List<String> cells = new ArrayList<>(values);
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0)
				line.append(COLUMN_GAP);
			String cell = cells.get(i);
			line.append(cell);
			// The last column is not padded, so lines carry no trailing spaces.
			if (i < cells.size() - 1)
				line.append(" ".repeat(widths[i] - cell.length()));
		}
		out.println(line);
	}

	/**
	 * Flushes both streams.
	 */
	public void flush() {
		out.flush();
		err.flush();
	}
}
