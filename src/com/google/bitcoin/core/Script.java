/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.bouncycastle.util.Arrays;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.bitcoin.core.Utils.bytesToHexString;

/**
 * BitCoin transactions don't specify what they do directly. Instead <a href="https://en.bitcoin.it/wiki/Script">a
 * small binary stack language</a> is used to define programs that when evaluated return whether the transaction
 * "accepts" or rejects the other transactions connected to it.<p>
 * 
 * This implementation of the scripting language is incomplete. It contains enough support to run standard
 * transactions generated by the official client, but non-standard transactions will fail.
 */
public class Script {
	private static Logger log = LoggerFactory.getLogger(Script.class);
	
    // Some constants used for decoding the scripts.
    public static final int OP_PUSHDATA1 = 76;
    public static final int OP_PUSHDATA2 = 77;
    public static final int OP_PUSHDATA4 = 78;
    public static final int OP_DUP = 118;
    public static final int OP_HASH160 = 169;
    public static final int OP_EQUALVERIFY = 136;
    public static final int OP_CHECKSIG = 172;

    byte[] program;
    private int cursor;
    
    // The program is a set of byte[]s where each element is either [opcode] or [data, data, data ...]
    private List<byte[]> chunks;
    byte[] programCopy;      // TODO: remove this
    private final NetworkParameters params;

    /**
     * Construct a Script using the given network parameters and a range of the programBytes array.
     * @param params Network parameters.
     * @param programBytes Array of program bytes from a transaction.
     * @param offset How many bytes into programBytes to start reading from.
     * @param length How many bytes to read.
     * @throws ScriptException
     */
    public Script(NetworkParameters params, byte[] programBytes, int offset, int length) throws ScriptException {
        this.params = params;
        parse(programBytes, offset, length);
    }

    /** Returns the program opcodes as a string, for example "[1234] DUP HAHS160" */
    public String toString() {
        StringBuffer buf = new StringBuffer();
        for (byte[] chunk : chunks) {
            if (chunk.length == 1) {
                String opName;
                int opcode = 0xFF & chunk[0];
                switch (opcode) {
                    case OP_DUP: opName = "DUP"; break;
                    case OP_HASH160: opName = "HASH160"; break;
                    case OP_CHECKSIG: opName = "CHECKSIG"; break;
                    case OP_EQUALVERIFY: opName = "EQUALVERIFY"; break;
                    default:
                        opName = "?(" + opcode + ")";
                        break;
                }
                buf.append(opName);
                buf.append(" ");
            } else {
                // Data chunk
                buf.append("[");
                buf.append(chunk.length);
                buf.append("]");
                buf.append(bytesToHexString(chunk));
                buf.append(" ");
            }
        }
        return buf.toString();
    }


    private byte[] getData(int len) throws ScriptException {
        try {
            byte[] buf = new byte[len];
            System.arraycopy(program, cursor, buf, 0, len);
            cursor += len;
            return buf;
        } catch (ArrayIndexOutOfBoundsException e) {
            // We want running out of data in the array to be treated as a handleable script parsing exception,
            // not something that abnormally terminates the app.
            throw new ScriptException("Failed read of " + len + " bytes", e);
        }
    }
    
    private int readByte() {
        return 0xFF & program[cursor++];
    }
    
    /**
     * To run a script, first we parse it which breaks it up into chunks representing pushes of
     * data or logical opcodes. Then we can run the parsed chunks.
     * 
     * The reason for this split, instead of just interpreting directly, is to make it easier
     * to reach into a programs structure and pull out bits of data without having to run it.
     * This is necessary to render the to/from addresses of transactions in a user interface.
     * The official client does something similar.
     */
    private void parse(byte[] programBytes, int offset, int length) throws ScriptException {
        // TODO: this is inefficient
        programCopy = new byte[length];
        System.arraycopy(programBytes, offset, programCopy, 0, length);

        program = programCopy;
        offset = 0;
        chunks = new ArrayList<byte[]>(10);  // Arbitrary choice of initial size.
        cursor = offset;
        while (cursor < offset + length) {
            int opcode = readByte();
            if (opcode >= 0xF0) {
                // Not a single byte opcode.
                opcode = (opcode << 8) | readByte();
            }
            
            if (opcode > 0 && opcode < OP_PUSHDATA1) {
                // Read some bytes of data, where how many is the opcode value itself.
                chunks.add(getData(opcode));  // opcode == len here.
            } else if (opcode == OP_PUSHDATA1) {
                int len = readByte();
                chunks.add(getData(len));
            } else if (opcode == OP_PUSHDATA2) {
                // Read a short, then read that many bytes of data.
                int len = readByte() | (readByte() << 8);
                chunks.add(getData(len));
            } else if (opcode == OP_PUSHDATA4) {
                // Read a uint32, then read that many bytes of data.
                log.error("PUSHDATA4: Unimplemented");
            } else {
                chunks.add(new byte[] { (byte) opcode });
            }
        }
    }

    /**
     * Returns true if this transaction is of a format that means it was a direct IP to IP transaction. These
     * transactions are deprecated and no longer used, support for creating them has been removed from the official
     * client.
     */
    public boolean isSentToIP() {
        if (chunks.size() != 2)
            return false;
        return (0xFF & chunks.get(1)[0]) == OP_CHECKSIG && chunks.get(0).length > 1;
    }
    
    /**
     * If a program matches the standard template DUP HASH160 <pubkey hash> EQUALVERIFY CHECKSIG
     * then this function retrieves the third element, otherwise it throws a ScriptException.
     * 
     * This is useful for fetching the destination address of a transaction.
     */
    public byte[] getPubKeyHash() throws ScriptException {
        if (chunks.size() != 5)
            throw new ScriptException("Script not of right size to be a scriptPubKey, " + 
                                      "expecting 5 but got " + chunks.size());
        if ((0xFF & chunks.get(0)[0]) != OP_DUP || 
            (0xFF & chunks.get(1)[0]) != OP_HASH160 ||
            (0xFF & chunks.get(3)[0]) != OP_EQUALVERIFY || 
            (0xFF & chunks.get(4)[0]) != OP_CHECKSIG)
            throw new ScriptException("Script not in the standard scriptPubKey form");
        
        // Otherwise, the third element is the hash of the public key, ie the bitcoin address.
        return chunks.get(2);
    }
    
    /**
     * If a program has two data buffers (constants) and nothing else, the second one is returned.
     * For a scriptSig this should be the public key of the sender.
     * 
     * This is useful for fetching the source address of a transaction.
     */
    public byte[] getPubKey() throws ScriptException {
        if (chunks.size() == 1) {
            // Direct IP to IP transactions only have the public key in their scriptSig.
            return chunks.get(0);
        }
        if (chunks.size() != 2)
            throw new ScriptException("Script not of right size to be a scriptSig, expecting 2" + 
                                       " but got " + chunks.size());
        if (!(chunks.get(0).length > 1) && (chunks.get(1).length > 1))
            throw new ScriptException("Script not in the standard scriptSig form: " +
            chunks.size() + " chunks");
        return chunks.get(1);
    }
    
    /**
     * Convenience wrapper around getPubKey. Only works for scriptSigs.
     */
    public Address getFromAddress() throws ScriptException {
        return new Address(params, Utils.sha256hash160(getPubKey()));
    }

    /**
     * Gets the destination address from this script, if it's in the required form (see getPubKey).
     * @throws ScriptException
     */
    public Address getToAddress() throws ScriptException {
        return new Address(params, getPubKeyHash());
    }

    ////////////////////// Interface for writing scripts from scratch ////////////////////////////////

    /** Writes out the given byte buffer to the output stream with the correct opcode prefix */
    static void writeBytes(OutputStream os,  byte[] buf) throws IOException {
        if (buf.length < OP_PUSHDATA1) {
            os.write(buf.length);
            os.write(buf);
        } else if (buf.length < 256) {
            os.write(OP_PUSHDATA1);
            os.write(buf.length);
            os.write(buf);
        } else if (buf.length < 65536) {
            os.write(OP_PUSHDATA2);
            os.write(0xFF & (buf.length));
            os.write(0xFF & (buf.length >> 8));
            os.write(buf);
        } else {
            throw new RuntimeException("Unimplemented");
        }
    }

    static byte[] createOutputScript(Address to) {
        try {
            // TODO: Do this by creating a Script *first* then having the script reassemble itself into bytes.
            ByteArrayOutputStream bits = new ByteArrayOutputStream();
            bits.write(OP_DUP);
            bits.write(OP_HASH160);
            writeBytes(bits, to.getHash160());
            bits.write(OP_EQUALVERIFY);
            bits.write(OP_CHECKSIG);
            return bits.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /** Create a script that sends coins directly to the given public key (eg in a coinbase transaction). */
    static byte[] createOutputScript(byte[] pubkey) {
        try {
            // TODO: Do this by creating a Script *first* then having the script reassemble itself into bytes.
            ByteArrayOutputStream bits = new ByteArrayOutputStream();
            writeBytes(bits, pubkey);
            bits.write(OP_CHECKSIG);
            return bits.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    static byte[] createInputScript(byte[] signature,  byte[] pubkey) {
        try {
            // TODO: Do this by creating a Script *first* then having the script reassemble itself into bytes.
            ByteArrayOutputStream bits = new ByteArrayOutputStream();
            writeBytes(bits, signature);
            writeBytes(bits, pubkey);
            return bits.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
