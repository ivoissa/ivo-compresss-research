package io.ivo.deflate;

import java.util.List;

/** In-memory exact representation of one raw RFC1951 stream. */
final class DeflateIr {
    sealed interface Block permits StoredBlock, CompressedBlock {
        boolean finalBlock();
        int type();
    }

    record StoredBlock(boolean finalBlock, int padBitCount, int padBits,
                       int lenField, int nlenField, byte[] data) implements Block {
        StoredBlock {
            if (padBitCount < 0 || padBitCount > 7 || (padBits >>> padBitCount) != 0 ||
                    lenField != data.length || (lenField ^ nlenField) != 0xffff)
                throw new IllegalArgumentException("Invalid stored block fields");
        }
        @Override public int type() { return 0; }
    }

    /** type is 1=fixed Huffman or 2=dynamic Huffman. */
    record CompressedBlock(boolean finalBlock, int type,
                           int headerBitCount, byte[] headerBits,
                           int[] literalRuns, byte[] literals,
                           int[] matchLengths, int[] matchDistances) implements Block {
        CompressedBlock {
            if (headerBitCount < 0 || headerBitCount > (long)headerBits.length * 8 ||
                    headerBits.length != (headerBitCount + 7L) / 8 || (type == 1 && headerBitCount != 0))
                throw new IllegalArgumentException("Invalid Huffman header slice");
            if (type != 1 && type != 2) throw new IllegalArgumentException("Compressed block type=" + type);
            if (literalRuns.length != matchLengths.length + 1 || matchLengths.length != matchDistances.length)
                throw new IllegalArgumentException("Inconsistent token streams");
        }
    }

    final List<Block> blocks;
    final int tailPadBitCount;
    final int tailPadBits;
    final byte[] trailingBytes;

    DeflateIr(List<Block> blocks, int tailPadBitCount, int tailPadBits, byte[] trailingBytes) {
        this.blocks = List.copyOf(blocks);
        this.tailPadBitCount = tailPadBitCount;
        this.tailPadBits = tailPadBits;
        this.trailingBytes = trailingBytes.clone();
    }
}
