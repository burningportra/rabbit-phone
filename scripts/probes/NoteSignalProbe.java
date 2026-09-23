import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** On-device diagnostic: exports only signal statistics, never decoded audio or text. */
public final class NoteSignalProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One local note path is required");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        long samples = 0;
        double squareSum = 0;
        double peak = 0;
        try {
            extractor.setDataSource(args[0]);
            MediaFormat format = null;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    extractor.selectTrack(track);
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IllegalArgumentException("No audio track");
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            boolean inputDone = false;
            boolean outputDone = false;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            long deadline = System.nanoTime() + 30_000_000_000L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Decode timed out");
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(index);
                        int count = extractor.readSampleData(input, 0);
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = decoder.getOutputFormat();
                    encoding = output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? output.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT
                            && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw new IllegalStateException("Unsupported decoded PCM format");
                    }
                } else if (index >= 0) {
                    ByteBuffer output = decoder.getOutputBuffer(index).order(ByteOrder.nativeOrder());
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    while (output.remaining() >= bytes) {
                        double value = bytes == 4 ? output.getFloat() : output.getShort() / 32768.0;
                        squareSum += value * value;
                        peak = Math.max(peak, Math.abs(value));
                        samples++;
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            if (samples == 0) throw new IllegalStateException("No decoded samples");
            double rms = Math.sqrt(squareSum / samples);
            System.out.printf(Locale.US,
                    "{\"samples\":%d,\"rms_dbfs\":%.2f,\"peak_dbfs\":%.2f,\"nonzero\":%s}%n",
                    samples, db(rms), db(peak), peak > 0 ? "true" : "false");
        } finally {
            if (decoder != null) decoder.release();
            extractor.release();
        }
    }

    private static double db(double amplitude) {
        return amplitude == 0 ? -160 : Math.max(-160, 20 * Math.log10(amplitude));
    }
}
