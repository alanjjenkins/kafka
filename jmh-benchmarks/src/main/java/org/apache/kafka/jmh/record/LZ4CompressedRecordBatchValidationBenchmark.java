package org.apache.kafka.jmh.record;

import org.apache.kafka.common.record.CompressionConfig;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 15)
public class LZ4CompressedRecordBatchValidationBenchmark extends AbstractCompressedRecordBatchValidationBenchmark {
    @Param(value = {"4", "5", "6", "7"})
    private int blockSize = 4;

    @Param(value = {"1", "5", "9", "13", "17"})
    private int level = 9;

    @Override
    CompressionConfig compressionConfig() {
        return CompressionConfig.lz4().setBlockSize(this.blockSize).setLevel(level).build();
    }
}
