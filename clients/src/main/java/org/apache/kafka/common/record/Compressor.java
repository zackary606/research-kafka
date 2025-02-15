/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.record;

import java.lang.reflect.Constructor;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.utils.Utils;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Compressor {

    static private final float COMPRESSION_RATE_DAMPING_FACTOR = 0.9f;
    static private final float COMPRESSION_RATE_ESTIMATION_FACTOR = 1.05f;
    static private final int COMPRESSION_DEFAULT_BUFFER_SIZE = 1024;

    private static final float[] TYPE_TO_RATE;

    static {
        int maxTypeId = -1;
        for (CompressionType type : CompressionType.values())
            maxTypeId = Math.max(maxTypeId, type.id);
        TYPE_TO_RATE = new float[maxTypeId + 1];
        for (CompressionType type : CompressionType.values()) {
            TYPE_TO_RATE[type.id] = type.rate;
        }
    }

    //动态加载snappy和lz4类，以避免在不使用压缩的情况下对运行时的依赖
    //缓存构造函数，以避免为每个批次调用Class.forName方法
    private static MemoizingConstructorSupplier snappyOutputStreamSupplier = new MemoizingConstructorSupplier(new ConstructorSupplier() {
        @Override
        public Constructor get() throws ClassNotFoundException, NoSuchMethodException {
            return Class.forName("org.xerial.snappy.SnappyOutputStream")
                .getConstructor(OutputStream.class, Integer.TYPE);
        }
    });

    private static MemoizingConstructorSupplier lz4OutputStreamSupplier = new MemoizingConstructorSupplier(new ConstructorSupplier() {
        @Override
        public Constructor get() throws ClassNotFoundException, NoSuchMethodException {
            return Class.forName("org.apache.kafka.common.record.KafkaLZ4BlockOutputStream")
                .getConstructor(OutputStream.class);
        }
    });

    private static MemoizingConstructorSupplier snappyInputStreamSupplier = new MemoizingConstructorSupplier(new ConstructorSupplier() {
        @Override
        public Constructor get() throws ClassNotFoundException, NoSuchMethodException {
            return Class.forName("org.xerial.snappy.SnappyInputStream")
                .getConstructor(InputStream.class);
        }
    });

    private static MemoizingConstructorSupplier lz4InputStreamSupplier = new MemoizingConstructorSupplier(new ConstructorSupplier() {
        @Override
        public Constructor get() throws ClassNotFoundException, NoSuchMethodException {
            return Class.forName("org.apache.kafka.common.record.KafkaLZ4BlockInputStream")
                .getConstructor(InputStream.class, Boolean.TYPE);
        }
    });

    private final CompressionType type;

    /**
     * 当写人数据超出 ByteBuffer 容量时 , ByteBufferOutputStream 会进行自动扩容
     */
    private final ByteBufferOutputStream bufferStream;

    /**
     * 对bufferStream做了装饰,为其添加了压缩功能
     */
    private final DataOutputStream appendStream;
    private final int initPos;

    public long writtenUncompressed;
    public long numRecords;
    public float compressionRate;
    public long maxTimestamp;

    /**
     *
     * @param buffer
     * @param type 从 KafkaProducer 传递过来的压缩类型
     */
    public Compressor(ByteBuffer buffer, CompressionType type) {
        this.type = type;
        this.initPos = buffer.position();

        this.numRecords = 0;
        this.writtenUncompressed = 0;
        this.compressionRate = 1;
        this.maxTimestamp = Record.NO_TIMESTAMP;

        if (type != CompressionType.NONE) {
            // for compressed records, leave space for the header and the shallow message metadata
            // and move the starting position to the value payload(有效载荷) offset
            buffer.position(initPos + Records.LOG_OVERHEAD + Record.RECORD_OVERHEAD);
        }

        // create the stream
        bufferStream = new ByteBufferOutputStream(buffer);
        //根据压缩类型创建合适 的压缩流
        appendStream = wrapForOutput(bufferStream, type, COMPRESSION_DEFAULT_BUFFER_SIZE);
    }

    public ByteBuffer buffer() {
        return bufferStream.buffer();
    }

    public double compressionRate() {
        return compressionRate;
    }

    public void close() {
        try {
            appendStream.close();
        } catch (IOException e) {
            throw new KafkaException(e);
        }

        if (type != CompressionType.NONE) {
            ByteBuffer buffer = bufferStream.buffer();
            int pos = buffer.position();
            // write the header, for the end offset write as number of records - 1
            buffer.position(initPos);
            buffer.putLong(numRecords - 1);
            buffer.putInt(pos - initPos - Records.LOG_OVERHEAD);
            // 写浅信息（crc和值大小还不正确）
            Record.write(buffer, maxTimestamp, null, null, type, 0, -1);
            // 计算填充值的大小
            int valueSize = pos - initPos - Records.LOG_OVERHEAD - Record.RECORD_OVERHEAD;
            buffer.putInt(initPos + Records.LOG_OVERHEAD + Record.KEY_OFFSET_V1, valueSize);
            // compute and fill the crc at the beginning of the message
            long crc = Record.computeChecksum(buffer,
                initPos + Records.LOG_OVERHEAD + Record.MAGIC_OFFSET,
                pos - initPos - Records.LOG_OVERHEAD - Record.MAGIC_OFFSET);
            Utils.writeUnsignedInt(buffer, initPos + Records.LOG_OVERHEAD + Record.CRC_OFFSET, crc);
            // reset the position
            buffer.position(pos);

            // update the compression ratio
            this.compressionRate = (float) buffer.position() / this.writtenUncompressed;
            TYPE_TO_RATE[type.id] = TYPE_TO_RATE[type.id] * COMPRESSION_RATE_DAMPING_FACTOR +
                compressionRate * (1 - COMPRESSION_RATE_DAMPING_FACTOR);
        }
    }

    // Note that for all the write operations below, IO exceptions should
    // never be thrown since the underlying ByteBufferOutputStream does not throw IOException;
    // therefore upon encountering this issue we just close the append stream.

    public void putLong(final long value) {
        try {
            appendStream.writeLong(value);
        } catch (IOException e) {
            throw new KafkaException("I/O exception when writing to the append stream, closing", e);
        }
    }

    public void putInt(final int value) {
        try {
            appendStream.writeInt(value);
        } catch (IOException e) {
            throw new KafkaException("I/O exception when writing to the append stream, closing", e);
        }
    }

    public void put(final ByteBuffer buffer) {
        try {
            appendStream.write(buffer.array(), buffer.arrayOffset(), buffer.limit());
        } catch (IOException e) {
            throw new KafkaException("I/O exception when writing to the append stream, closing", e);
        }
    }

    public void putByte(final byte value) {
        try {
            appendStream.write(value);
        } catch (IOException e) {
            throw new KafkaException("I/O exception when writing to the append stream, closing", e);
        }
    }

    public void put(final byte[] bytes, final int offset, final int len) {
        try {
            appendStream.write(bytes, offset, len);
        } catch (IOException e) {
            throw new KafkaException("I/O exception when writing to the append stream, closing", e);
        }
    }

    /**
     * @return CRC of the record
     */
    public long putRecord(long timestamp, byte[] key, byte[] value, CompressionType type,
                          int valueOffset, int valueSize) {
        // put a record as un-compressed into the underlying stream
        long crc = Record.computeChecksum(timestamp, key, value, type, valueOffset, valueSize);
        byte attributes = Record.computeAttributes(type);
        putRecord(crc, attributes, timestamp, key, value, valueOffset, valueSize);
        return crc;
    }

    /**
     * Put a record as uncompressed into the underlying stream
     * @return CRC of the record
     */
    public long putRecord(long timestamp, byte[] key, byte[] value) {
        return putRecord(timestamp, key, value, CompressionType.NONE, 0, -1);
    }

    private void putRecord(final long crc, final byte attributes, final long timestamp, final byte[] key, final byte[] value, final int valueOffset, final int valueSize) {
        maxTimestamp = Math.max(maxTimestamp, timestamp);
        Record.write(this, crc, attributes, timestamp, key, value, valueOffset, valueSize);
    }

    public void recordWritten(int size) {
        numRecords += 1;
        writtenUncompressed += size;
    }

    public long numRecordsWritten() {
        return numRecords;
    }

    /**
     * 估计写入字节大小(在判断 MemoryRecords 是否写满的逻辑中使用)<br>
     * CompressionType.NONE:直接返回buffer.position;否则返回 写人的未压缩数据的字节数*指定压缩方式的压缩率*估算因子
     * @return
     */
    public long estimatedBytesWritten() {
        if (type == CompressionType.NONE) {
            return bufferStream.buffer().position();
        } else {
            // 根据未压缩的写入字节估计写入底层字节缓冲区的写入字节
            return (long) (writtenUncompressed * TYPE_TO_RATE[type.id] * COMPRESSION_RATE_ESTIMATION_FACTOR);
        }
    }

    // 以下两个函数也需要公开，因为它们在MemoryRecords.iteration中使用
    public static DataOutputStream wrapForOutput(ByteBufferOutputStream buffer, CompressionType type, int bufferSize) {
        try {
            switch (type) {
                case NONE:
                    return new DataOutputStream(buffer);
                case GZIP://GZIP是JDK自带的包,可以直接使用new创建实例
                    return new DataOutputStream(new GZIPOutputStream(buffer, bufferSize));
                case SNAPPY://Snappy 则需要引入额外的依赖包,为了在不使用 Snappy 压缩方式时,减少依赖包,这里使用反射的方式动态创建
                    try {
                        OutputStream stream = (OutputStream) snappyOutputStreamSupplier.get().newInstance(buffer, bufferSize);
                        return new DataOutputStream(stream);
                    } catch (Exception e) {
                        throw new KafkaException(e);
                    }
                case LZ4:
                    try {
                        OutputStream stream = (OutputStream) lz4OutputStreamSupplier.get().newInstance(buffer);
                        return new DataOutputStream(stream);
                    } catch (Exception e) {
                        throw new KafkaException(e);
                    }
                default:
                    throw new IllegalArgumentException("Unknown compression type: " + type);
            }
        } catch (IOException e) {
            throw new KafkaException(e);
        }
    }

    public static DataInputStream wrapForInput(ByteBufferInputStream buffer, CompressionType type, byte messageVersion) {
        try {
            switch (type) {
                case NONE:
                    return new DataInputStream(buffer);
                case GZIP:
                    return new DataInputStream(new GZIPInputStream(buffer));
                case SNAPPY:
                    try {
                        InputStream stream = (InputStream) snappyInputStreamSupplier.get().newInstance(buffer);
                        return new DataInputStream(stream);
                    } catch (Exception e) {
                        throw new KafkaException(e);
                    }
                case LZ4:
                    try {
                        InputStream stream = (InputStream) lz4InputStreamSupplier.get().newInstance(buffer,
                                messageVersion == Record.MAGIC_VALUE_V0);
                        return new DataInputStream(stream);
                    } catch (Exception e) {
                        throw new KafkaException(e);
                    }
                default:
                    throw new IllegalArgumentException("Unknown compression type: " + type);
            }
        } catch (IOException e) {
            throw new KafkaException(e);
        }
    }

    private interface ConstructorSupplier {
        Constructor get() throws ClassNotFoundException, NoSuchMethodException;
    }

    // this code is based on Guava's @see{com.google.common.base.Suppliers.MemoizingSupplier}
    private static class MemoizingConstructorSupplier {
        final ConstructorSupplier delegate;
        transient volatile boolean initialized;
        transient Constructor value;

        public MemoizingConstructorSupplier(ConstructorSupplier delegate) {
            this.delegate = delegate;
        }

        public Constructor get() throws NoSuchMethodException, ClassNotFoundException {
            if (!initialized) {
                synchronized (this) {
                    if (!initialized) {
                        Constructor constructor = delegate.get();
                        value = constructor;
                        initialized = true;
                        return constructor;
                    }
                }
            }
            return value;
        }
    }
}
