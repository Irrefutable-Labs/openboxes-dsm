package org.pih.warehouse.dsm

import groovy.transform.CompileStatic

/**
 * Base32 Crockford, as DSM shows its ids at every human edge (no hex on a DSM path). The same alphabet, the same
 * zero-padded tail and the same decoding substitutions as DSM's own encoder
 * (dsm/src/utils/text_id.rs: encode_base32_crockford, decode_base32_crockford), so an id read from a host and
 * written back to it is the same bytes.
 */
@CompileStatic
class Base32Crockford {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A 32-byte DSM id (a device id, a policy anchor, a transfer hash) encodes to this many characters. */
    static final int ID32_LENGTH = 52

    static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4).intdiv(5))
        int buffer = 0
        int bitsLeft = 0
        for (byte b : bytes) {
            buffer = ((buffer << 8) | (b & 0xFF)) & 0xFFFF
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F))
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F))
        }
        return out.toString()
    }

    /**
     * The bytes `text` encodes. Hyphens and spaces are skipped, lowercase is read as uppercase, and the
     * Crockford substitutions (O for 0; I and L for 1) are read as the digits they stand for. Leftover bits that
     * make no whole byte are dropped, as the encoder zero-pads them.
     *
     * @throws IllegalArgumentException when a character is not Base32 Crockford
     */
    static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream((text.length() * 5).intdiv(8))
        long buffer = 0
        int bitsLeft = 0
        for (char c : text.toCharArray()) {
            if (c == ('-' as char) || c == (' ' as char)) {
                continue
            }
            int value = valueOf(c)
            if (value < 0) {
                throw new IllegalArgumentException("not Base32 Crockford: ${text}")
            }
            buffer = ((buffer << 5) | value) & 0xFFFFFFFFL
            bitsLeft += 5
            while (bitsLeft >= 8) {
                out.write((int) ((buffer >> (bitsLeft - 8)) & 0xFF))
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** The 32 bytes `text` names, or a refusal naming what it was supposed to be. */
    static byte[] decodeId32(String text, String what) {
        byte[] bytes
        try {
            bytes = decode(text)
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("${what} is not Base32 Crockford: ${e.message}", e)
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("${what} is ${bytes.length} bytes, not 32")
        }
        return bytes
    }

    private static int valueOf(char c) {
        char upper = Character.toUpperCase(c)
        switch (upper) {
            case ('O' as char): return 0
            case ('I' as char):
            case ('L' as char): return 1
            default: return ALPHABET.indexOf((int) upper)
        }
    }
}
