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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * How values are shown to people. An absent value is shown as {@code -}, so that table columns and
 * details never look blank by accident.
 */
public final class Formats {
	/** What an absent value is shown as. */
	public static final String NONE = "-";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private Formats() {
	}

	/**
	 * Formats a point in time in the local time zone.
	 *
	 * @param epochMillis the time in epoch milliseconds, or {@code 0} or less for none
	 * @return the time, such as {@code 2026-09-16 14:03:22}, or {@code -}
	 */
	public static String time(long epochMillis) {
		if (epochMillis <= 0)
			return NONE;
		return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}

	/**
	 * Formats a text value.
	 *
	 * @param value the text, or {@code null}
	 * @return the text, or {@code -} if it is absent or empty
	 */
	public static String text(String value) {
		return value == null || value.isEmpty() ? NONE : value;
	}

	/**
	 * Formats an optional text value.
	 *
	 * @param value the text
	 * @return the text, or {@code -} if it is absent or empty
	 */
	public static String text(Optional<String> value) {
		return text(value.orElse(null));
	}

	/**
	 * Formats any value with its {@code toString()}.
	 *
	 * @param value the value, or {@code null}
	 * @return the text, or {@code -}
	 */
	public static String value(Object value) {
		if (value instanceof Optional<?> optional)
			return optional.map(Formats::value).orElse(NONE);
		if (value instanceof BigDecimal decimal)
			return decimal.toPlainString();
		return value == null ? NONE : text(value.toString());
	}

	/**
	 * Formats a flag.
	 *
	 * @param value the flag
	 * @return {@code yes} or {@code no}
	 */
	public static String yesNo(boolean value) {
		return value ? "yes" : "no";
	}

	/**
	 * Formats a list of values, separated by commas.
	 *
	 * @param values the values
	 * @return the list, or {@code -} if it is empty
	 */
	public static String list(Collection<?> values) {
		if (values == null || values.isEmpty())
			return NONE;
		return String.join(", ", values.stream().map(String::valueOf).toList());
	}

	/**
	 * Shortens a text value for a table column, on one line.
	 *
	 * @param value     the text, or {@code null}
	 * @param maxLength the longest the result may be
	 * @return the text, or {@code -}
	 */
	public static String brief(String value, int maxLength) {
		String text = text(value).replaceAll("\\s+", " ");
		return text.length() <= maxLength ? text : text.substring(0, Math.max(0, maxLength - 3)) + "...";
	}

	/**
	 * Formats a size in bytes for people: bytes below 1 KiB, otherwise binary units with one decimal.
	 *
	 * @param bytes the size
	 * @return the size, such as {@code 512 B} or {@code 1.5 MiB}
	 */
	public static String bytes(long bytes) {
		if (bytes < 1024)
			return bytes + " B";

		String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/**
	 * Returns a count with its noun, singular or plural.
	 *
	 * @param count    the count
	 * @param singular the noun for one, such as {@code "user"}
	 * @param plural   the noun for any other count, such as {@code "users"}
	 * @return the count with its noun, such as {@code "3 users"}
	 */
	public static String count(long count, String singular, String plural) {
		return count + " " + (count == 1 ? singular : plural);
	}
}
