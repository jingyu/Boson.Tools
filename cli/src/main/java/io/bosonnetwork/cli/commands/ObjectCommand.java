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

package io.bosonnetwork.cli.commands;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.TypeConversionException;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.cli.common.Listing;
import io.bosonnetwork.cli.common.PageOptions;
import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.cli.services.ServiceViews;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.SecretStream;
import io.bosonnetwork.ionstore.GetRequest;
import io.bosonnetwork.ionstore.IonObject;
import io.bosonnetwork.ionstore.IonStore;
import io.bosonnetwork.ionstore.PutRequest;
import io.bosonnetwork.ionstore.exceptions.DecryptionException;
import io.bosonnetwork.web.PaginatedResult;

/**
 * The {@code object} commands of {@code boson-cli}: objects in the super node's Ion Store.
 * <p>
 * An object is named by its id on the user's own super node, or by its {@code ions://<peerId>/<id>}
 * address on any node; the node fetches an object held elsewhere.
 */
@Command(name = "object", aliases = "ion",
		description = {"Store, retrieve, list and remove objects in the super node's Ion Store.",
				"An object is named by its id on your super node, or by its ions:// address on any node. Storing, "
						+ "listing and removing use this machine's device; retrieving needs no account."},
		subcommands = {ObjectCommand.PutCommand.class, ObjectCommand.GetCommand.class, ObjectCommand.ListCommand.class,
				ObjectCommand.ShowCommand.class, ObjectCommand.RemoveCommand.class})
public class ObjectCommand extends CliGroup {
	private static final String REFERENCE_DESCRIPTION = "The object: its id, or its ions://<peer-id>/<object-id> address.";
	private static final String STDIO = "-";

	@Command(name = "put", aliases = "upload", description = {"Store a file as an object.",
			"Prints the object's id and its address, which is what others retrieve it by. With --encrypt, the file is "
					+ "encrypted here with a new key, which is printed: the node never sees it, and the object cannot be "
					+ "read without it."})
	public static class PutCommand extends DirectorCommand {
		@Parameters(paramLabel = "<file>", description = "The file to store, or - for standard input.")
		String file;

		@Option(names = "--name", paramLabel = "<name>", description = "The object's name. Default: the file's name.")
		String name;

		@Option(names = "--type", paramLabel = "<content-type>",
				description = "The object's content type, such as image/png. Default: guessed from the file.")
		String contentType;

		@Option(names = "--ttl", paramLabel = "<duration>", converter = DurationConverter.class,
				description = "How long the node keeps the object: seconds, or a number with s, m, h or d, such as 7d. "
						+ "Default: the node's.")
		Long ttl;

		@Option(names = "--encrypt", description = "Encrypt the object with a new key, which is printed.")
		boolean encrypt;

		@Option(names = "--meta", paramLabel = "<key=value>",
				description = "Metadata to keep with the object; repeat for more. Keys become Ion-<key>.")
		Map<String, String> metadata;

		@Override
		protected void run() throws Exception {
			boolean stdin = file.equals(STDIO);
			Path path = stdin ? null : Path.of(file);
			if (path != null && !Files.isRegularFile(path))
				throw CliException.usage(Files.isDirectory(path) ? file + " is a directory; store one file at a time." :
						"No such file: " + file + ".", null);

			IonStore store = context().ionStore();
			PutRequest request = store.put();
			if (name != null)
				request.name(name);
			if (contentType != null)
				request.contentType(contentType);
			if (ttl != null)
				request.ttl(ttl);
			if (metadata != null)
				metadata.forEach(request::metadata);

			byte[] key = encrypt ? Random.randomBytes(SecretStream.KEY_BYTES) : null;
			if (key != null)
				request.encrypt(key);

			output().progress("Storing " + (stdin ? "standard input" : path.getFileName()) + " in the Ion Store at " +
					store.getServiceUrl() + "...");
			IonObject object;
			if (stdin) {
				InputStream in = context().environment().binaryIn();
				object = await(request.content(in).send());
			} else {
				object = await(request.content(path).send());
			}

			if (output().isJson()) {
				Map<String, Object> json = ServiceViews.objectJson(object);
				json.put("key", key != null ? Keys.encode(key, false) : null);
				output().json(json);
				return;
			}

			output().message("Stored " + Formats.text(object.getName()) + " as object " + object.getId() + ".");
			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("Address", object.getUri());
			rows.put("Size", Formats.bytes(object.getPlainTextSize()));
			rows.put("Expires", object.getExpireAt() > 0 ? Formats.time(object.getExpireAt() * 1000) : "never");
			if (key != null)
				rows.put("Key", Keys.encode(key, false));
			output().details(rows);
			if (key != null)
				output().message("Keep the key: without it, nobody - you included - can read the object. " +
						"Retrieve it with " + tool().command("object get " + object.getUri() + " --key <key>") + ".");
		}
	}

	@Command(name = "get", aliases = "download", description = {"Retrieve an object.",
			"Writes it to a file named after the object, or to the file --output names. An existing file is replaced "
					+ "only with --overwrite, and only once the object has been retrieved and verified."})
	public static class GetCommand extends DirectorCommand {
		@Parameters(paramLabel = "<object>", description = REFERENCE_DESCRIPTION)
		String reference;

		@Option(names = {"-o", "--output"}, paramLabel = "<file>",
				description = "The file to write, or - for standard output. Default: the object's name, in the "
						+ "current directory.")
		String output;

		@Option(names = "--overwrite", description = "Replace the file if it exists.")
		boolean overwrite;

		@Option(names = "--key", paramLabel = "<key>",
				description = "The key of an encrypted object, as 'object put --encrypt' printed it.")
		String key;

		@Option(names = "--raw", description = "Retrieve an encrypted object as it is stored, without decrypting it.")
		boolean raw;

		@Override
		protected void run() throws Exception {
			if (key != null && raw)
				throw CliException.usage("--key decrypts the object and --raw keeps it encrypted; pass one of them.", null);
			byte[] secret = key != null ? decodeKey(key) : null;
			boolean toStdout = STDIO.equals(output);
			if (toStdout && output().isJson())
				throw CliException.usage("--json and --output - both write to standard output.",
						"Write the object to a file, or leave out --json.");

			IonStore store = context().ionStoreForRetrieval();
			Reference ref = Reference.parse(reference);
			GetRequest request = ref.request(store);
			if (secret != null)
				request.decrypt(secret);
			if (raw)
				request.raw();

			try {
				if (toStdout) {
					Optional<IonObject> object = await(request.toOutputStream(context().environment().binaryOut()));
					object.orElseThrow(() -> notFound(ref));
					return;
				}

				Path target = output != null ? Path.of(output) : Path.of(defaultName(store, ref));
				if (Files.isDirectory(target))
					throw CliException.usage(target + " is a directory.", "Name the file to write with --output <file>.");
				if (!overwrite && Files.exists(target))
					throw existing(target);

				Path directory = target.toAbsolutePath().getParent();
				Path partial = Files.createTempFile(directory, "." + target.getFileName() + ".", ".part");
				IonObject object;
				try {
					object = await(request.toFile(partial)).orElseThrow(() -> notFound(ref));
					try {
						if (overwrite)
							Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
						else
							Files.move(partial, target);
					} catch (FileAlreadyExistsException e) {
						throw existing(target);
					}
				} finally {
					Files.deleteIfExists(partial);
				}

				report(object, target);
			} catch (DecryptionException e) {
				throw decryption(e);
			}
		}

		private void report(IonObject object, Path target) throws IOException {
			long size = Files.size(target);
			if (output().isJson()) {
				Map<String, Object> json = ServiceViews.objectJson(object);
				json.put("file", target.toString());
				json.put("written", size);
				output().json(json);
				return;
			}

			output().message("Saved object " + object.getId() + (object.getName() != null ? " (" + object.getName() + ")" : "") +
					" to " + target + ", " + Formats.bytes(size) + (raw && object.isEncrypted() ? ", still encrypted." : "."));
		}

		private CliException decryption(DecryptionException e) {
			if (key == null)
				return CliException.failed("Object " + reference + " is encrypted.",
						"Pass the key it was stored with, --key <key>, or keep it encrypted with --raw.");
			if (e.getMessage() != null && e.getMessage().contains("not encrypted"))
				return CliException.usage("Object " + reference + " is not encrypted.", "Leave out --key.");
			return CliException.failed("Object " + reference + " cannot be decrypted with this key.",
					"Check the key: it is the one 'object put --encrypt' printed.");
		}

		// The object's own name when the node knows it, and the object id otherwise; never a path.
		private String defaultName(IonStore store, Reference ref) throws Exception {
			if (ref.isLocal(store)) {
				Optional<IonObject> object = await(store.getIonObject(ref.id()));
				String name = object.map(IonObject::getName).orElse(null);
				if (name != null) {
					String base = Path.of(name).getFileName().toString();
					if (!base.isBlank() && !base.equals(".") && !base.equals(".."))
						return base;
				}
			}
			return ref.id().toBase58String();
		}

		private static CliException existing(Path file) {
			return CliException.failed("The file " + file + " already exists; it was not changed.",
					"Choose another file with --output, or pass --overwrite.");
		}
	}

	@Command(name = "list", description = "List your objects, newest first.")
	public static class ListCommand extends DirectorCommand {
		@Mixin
		PageOptions page;

		@Override
		protected void run() throws Exception {
			IonStore store = context().ionStore();
			PaginatedResult<IonObject> objects = page.fetch(this, store::list);
			Listing.page(output(), objects, page, "objects", ServiceViews.OBJECT_HEADERS, ServiceViews::objectRow,
					ServiceViews::objectJson, "You have no objects. Store one with " + tool().command("object put <file>") + ".");
		}
	}

	@Command(name = "show", description = {"Show an object's details, without retrieving it.",
			"Only objects on your super node can be shown."})
	public static class ShowCommand extends DirectorCommand {
		@Parameters(paramLabel = "<object>", description = REFERENCE_DESCRIPTION)
		String reference;

		@Override
		protected void run() throws Exception {
			IonStore store = context().ionStoreForRetrieval();
			Reference ref = Reference.parse(reference);
			ref.requireLocal(store, "shown");

			IonObject object = await(store.getIonObject(ref.id())).orElseThrow(() -> notFound(ref));
			if (output().isJson())
				output().json(ServiceViews.objectJson(object));
			else
				output().details(ServiceViews.object(object));
		}
	}

	@Command(name = "remove", aliases = "delete", description = {"Remove your objects.",
			"Anyone who has an object's address can no longer retrieve it. Only objects you stored can be removed."})
	public static class RemoveCommand extends DirectorCommand {
		@Parameters(paramLabel = "<object>", arity = "1..*", description = "The objects: ids, or addresses on your super node.")
		List<String> references;

		@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
		boolean yes;

		@Override
		protected void run() throws Exception {
			IonStore store = context().ionStore();
			List<Reference> refs = new ArrayList<>();
			for (String reference : references) {
				Reference ref = Reference.parse(reference);
				ref.requireLocal(store, "removed");
				refs.add(ref);
			}

			terminal().confirm(refs.size() == 1 ? "Remove object " + refs.get(0).id() + "? It cannot be recovered." :
					"Remove " + refs.size() + " objects? They cannot be recovered.", yes);

			List<Id> removed = new ArrayList<>();
			List<Id> missing = new ArrayList<>();
			for (Reference ref : refs)
				(await(store.delete(ref.id())) ? removed : missing).add(ref.id());

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("removed", removed);
				json.put("notFound", missing);
				output().json(json);
			} else {
				removed.forEach(id -> output().message("Removed object " + id + "."));
			}

			if (!missing.isEmpty()) {
				String ids = String.join(", ", missing.stream().map(Id::toBase58String).toList());
				throw CliException.notFound("You have no " + (missing.size() == 1 ? "object " : "objects ") + ids + ".",
						"List your objects with " + tool().command("object list") + ".");
			}
		}
	}

	/**
	 * An object named on the command line: an id, or an {@code ions://} address.
	 *
	 * @param text the name as given
	 * @param uri  the address, or {@code null} for an id
	 * @param id   the object id
	 */
	record Reference(String text, URI uri, Id id) {
		private static final Pattern ADDRESS = Pattern.compile("(?i)ions://([^/]+)/([^/?#]+)");

		static Reference parse(String text) {
			String value = text.strip();
			if (value.contains("://")) {
				Matcher m = ADDRESS.matcher(value);
				if (m.matches()) {
					try {
						Id.of(m.group(1));
						return new Reference(value, URI.create(value), Id.of(m.group(2)));
					} catch (IllegalArgumentException e) {
						// reported below
					}
				}
				throw CliException.usage("'" + text + "' is not an object address.",
						"An address is ions://<peer-id>/<object-id>, as 'object put' prints it.");
			}

			try {
				return new Reference(value, null, Id.of(value));
			} catch (IllegalArgumentException e) {
				throw CliException.usage("'" + text + "' is neither an object id nor an object address.",
						"Name an object by its id, or by its address, ions://<peer-id>/<object-id>.");
			}
		}

		GetRequest request(IonStore store) {
			return uri != null ? store.get(uri) : store.get(id);
		}

		boolean isLocal(IonStore store) {
			return uri == null || Id.of(uri.getAuthority()).equals(store.getServicePeerId());
		}

		void requireLocal(IonStore store, String what) {
			if (!isLocal(store))
				throw CliException.usage("Object " + text + " is on another node; only objects on your super node can be " +
						what + ".", what.equals("shown") ? "Retrieve it with 'object get " + text + "'." : null);
		}
	}

	private static CliException notFound(Reference ref) {
		return CliException.notFound("No object " + ref.text() + " was found.", ref.uri() == null ?
				"An id names an object on your super node; an object on another node is named by its address, " +
						"ions://<peer-id>/<object-id>." : "The object may have expired, or been removed.");
	}

	private static byte[] decodeKey(String text) {
		byte[] key = Keys.decode(text, "key");
		if (key.length != SecretStream.KEY_BYTES)
			throw CliException.usage("The key is " + key.length + " bytes, but an object key is " + SecretStream.KEY_BYTES + ".",
					"Use the key 'object put --encrypt' printed.");
		return key;
	}

	/**
	 * Reads a duration: seconds, or a number with {@code s}, {@code m}, {@code h} or {@code d}.
	 */
	static class DurationConverter implements ITypeConverter<Long> {
		private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smhd]?)");

		@Override
		public Long convert(String value) {
			Matcher m = DURATION.matcher(value.strip().toLowerCase(Locale.ROOT));
			if (!m.matches())
				throw new TypeConversionException("'" + value + "' is not a duration, such as 3600, 90m, 12h or 7d");

			long unit = switch (m.group(2)) {
				case "m" -> 60;
				case "h" -> 3600;
				case "d" -> 86400;
				default -> 1;
			};
			try {
				long seconds = Math.multiplyExact(Long.parseLong(m.group(1)), unit);
				if (seconds <= 0)
					throw new TypeConversionException("the duration must be more than 0");
				return seconds;
			} catch (ArithmeticException | NumberFormatException e) {
				throw new TypeConversionException("'" + value + "' is too long a duration");
			}
		}
	}
}
