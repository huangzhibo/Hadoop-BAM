// Copied because we have a different SAMRecord.
//
// Required because of SAMRecord and BAMFileWriter.

/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package fi.tkk.ics.hadoop.bam.custom.samtools;

import net.sf.samtools.SAMBinaryTagAndValue;
import net.sf.samtools.SAMFormatException;
import net.sf.samtools.util.BinaryCodec;
import net.sf.samtools.util.RuntimeEOFException;
import net.sf.samtools.util.SortingCollection;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Class for translating between in-memory and disk representation of BAMRecord.
 */
public class BAMRecordCodec implements SortingCollection.Codec<SAMRecord> {
	static final int FIXED_BLOCK_SIZE = 8 * 4;
	static final int MAXIMUM_RECORD_LENGTH = 1024 * 1024;

    private final BinaryCigarCodec cigarCodec = new BinaryCigarCodec();
    private final SAMFileHeader header;
    private final BinaryCodec binaryCodec = new BinaryCodec();
    private final BinaryTagCodec binaryTagCodec = new BinaryTagCodec(binaryCodec);

    public BAMRecordCodec(final SAMFileHeader header) {
        this.header = header;
    }

    public BAMRecordCodec clone() {
        // Do not clone the references to codecs, as they must be distinct for each instance.
        BAMRecordCodec other = new BAMRecordCodec(this.header);
        return other;
    }


    /** Sets the output stream that records will be written to. */
    public void setOutputStream(final OutputStream os) {
        this.binaryCodec.setOutputStream(os);
    }

    /** Sets the input stream that records will be read from. */
    public void setInputStream(final InputStream is) {
        this.binaryCodec.setInputStream(is);
    }

    /**
     * Write object to OutputStream.
     * The SAMRecord must have a header set into it so reference indices can be resolved.
     *
     * @param alignment Record to be written.
     */
    public void encode(final SAMRecord alignment) {
        // Compute block size, as it is the first element of the file representation of SAMRecord
        final int readLength = alignment.getReadLength();

        final int cigarLength = alignment.getCigarLength();

        int blockSize = FIXED_BLOCK_SIZE + alignment.getReadNameLength() + 1  + // null terminated
                        cigarLength * 4 +
                        (readLength + 1) / 2 + // 2 bases per byte, round up
                        readLength;

        final int attributesSize = alignment.getAttributesBinarySize();
        if (attributesSize != -1) {
            // binary attribute size already known, don't need to compute.
            blockSize += attributesSize;
        } else {
            if (alignment.getBinaryAttributes() != null) {
                for (final SAMBinaryTagAndValue attribute : alignment.getBinaryAttributes()) {
                    blockSize += (BinaryTagCodec.getTagSize(attribute.value));
                }
            }
        }

        int indexBin = 0;
        if (alignment.getReferenceIndex() >= 0) {
            if (alignment.getIndexingBin() != null) {
                indexBin = alignment.getIndexingBin();
            } else {
                indexBin = alignment.computeIndexingBin();
            }
        }

        // Blurt out the elements
        this.binaryCodec.writeInt(blockSize);
        this.binaryCodec.writeInt(alignment.getReferenceIndex());
        // 0-based!!
        this.binaryCodec.writeInt(alignment.getAlignmentStart() - 1);
        this.binaryCodec.writeUByte((short)(alignment.getReadNameLength() + 1));
        this.binaryCodec.writeUByte((short)alignment.getMappingQuality());
        this.binaryCodec.writeUShort(indexBin);
        this.binaryCodec.writeUShort(cigarLength);
        this.binaryCodec.writeUShort(alignment.getFlags());
        this.binaryCodec.writeInt(alignment.getReadLength());
        this.binaryCodec.writeInt(alignment.getMateReferenceIndex());
        this.binaryCodec.writeInt(alignment.getMateAlignmentStart() - 1);
        this.binaryCodec.writeInt(alignment.getInferredInsertSize());
        final byte[] variableLengthBinaryBlock = alignment.getVariableBinaryRepresentation();
        if (variableLengthBinaryBlock != null) {
            // Don't need to encode variable-length block, because it is unchanged from
            // when the record was read from a BAM file.
            this.binaryCodec.writeBytes(variableLengthBinaryBlock);
        } else {
            if (alignment.getReadLength() != alignment.getBaseQualities().length &&
                alignment.getBaseQualities().length != 0) {
                throw new RuntimeException("Mismatch between read length and quals length writing read " +
                alignment.getReadName() + "; read length: " + alignment.getReadLength() +
                "; quals length: " + alignment.getBaseQualities().length);
            }
            this.binaryCodec.writeString(alignment.getReadName(), false, true);
            final int[] binaryCigar = cigarCodec.encode(alignment.getCigar());
            for (final int cigarElement : binaryCigar) {
                // Assumption that this will fit into an integer, despite the fact
                // that it is specced as a uint.
                this.binaryCodec.writeInt(cigarElement);
            }
            this.binaryCodec.writeBytes(SAMUtils.bytesToCompressedBases(alignment.getReadBases()));
            byte[] qualities = alignment.getBaseQualities();
            if (qualities.length == 0) {
                qualities = new byte[alignment.getReadLength()];
                Arrays.fill(qualities, (byte) 0xFF);
            }
            this.binaryCodec.writeBytes(qualities);
            if (alignment.getBinaryAttributes() != null) {
                for (final SAMBinaryTagAndValue attribute : alignment.getBinaryAttributes()) {
                    this.binaryTagCodec.writeTag(attribute.tag, attribute.value);
                }
            }
        }
    }

    /**
     * Read the next record from the input stream and convert into a java object.
     *
     * @return null if no more records.  Should throw exception if EOF is encountered in the middle of
     *         a record.
     */
    public SAMRecord decode() {
        int recordLength = 0;
        try {
            recordLength = this.binaryCodec.readInt();
        }
        catch (RuntimeEOFException e) {
            return null;
        }

        if (recordLength < FIXED_BLOCK_SIZE ||
                recordLength > MAXIMUM_RECORD_LENGTH) {
            throw new SAMFormatException("Invalid record length: " + recordLength);
        }
        
        final int referenceID = this.binaryCodec.readInt();
        final int coordinate = this.binaryCodec.readInt() + 1;
        final short readNameLength = this.binaryCodec.readUByte();
        final short mappingQuality = this.binaryCodec.readUByte();
        final int bin = this.binaryCodec.readUShort();
        final int cigarLen = this.binaryCodec.readUShort();
        final int flags = this.binaryCodec.readUShort();
        final int readLen = this.binaryCodec.readInt();
        final int mateReferenceID = this.binaryCodec.readInt();
        final int mateCoordinate = this.binaryCodec.readInt() + 1;
        final int insertSize = this.binaryCodec.readInt();
        final byte[] restOfRecord = new byte[recordLength - FIXED_BLOCK_SIZE];
        this.binaryCodec.readBytes(restOfRecord);
        final BAMRecord ret = new BAMRecord(header, referenceID, coordinate, readNameLength, mappingQuality,
                bin, cigarLen, flags, readLen, mateReferenceID, mateCoordinate, insertSize, restOfRecord);
        ret.setHeader(header); 
        return ret;
    }
}