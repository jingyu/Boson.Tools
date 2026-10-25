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

package io.bosonnetwork.node.cli.shell;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.Signature;

/**
 * {@code keygen}: a new key pair, for trying things out in the shell.
 */
@Command(name = "keygen", description = "Generate a key pair.")
public class KeygenCommand extends ShellCommandBase {
	@Option(names = "--hex", description = "Print the keys as hex rather than Base58.")
	boolean hex;

	@Override
	protected void run() {
		Signature.KeyPair signature = Signature.KeyPair.random();
		CryptoBox.KeyPair encryption = CryptoBox.KeyPair.fromSignatureKeyPair(signature);

		output().message("Id:          " + Id.of(signature.publicKey().bytes()));
		output().message("Signature key pair (Ed25519)");
		output().message("  Private key: " + Keys.encode(signature.privateKey().bytes(), hex));
		output().message("  Public key:  " + Keys.encode(signature.publicKey().bytes(), hex));
		output().message("Encryption key pair (Curve25519)");
		output().message("  Private key: " + Keys.encode(encryption.privateKey().bytes(), hex));
		output().message("  Public key:  " + Keys.encode(encryption.publicKey().bytes(), hex));
	}
}
