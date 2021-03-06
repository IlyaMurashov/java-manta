package com.joyent.manta.client;

import com.google.api.client.util.IOUtils;
import com.joyent.manta.client.config.IntegrationTestConfigContext;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.exception.MantaCryptoException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.UUID;


/**
 * Tests for verifying the behavior of {@link SeekableByteChannel} with
 * {@link MantaClient}.
 *
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 */
@Test(groups = { "seekable" })
public class MantaClientSeekableByteChannelIT {
    private static final String TEST_DATA = "EPISODEII_IS_BEST_EPISODE";

    private MantaClient mantaClient;

    private String testPathPrefix;


    @BeforeClass
    @Parameters({"manta.url", "manta.user", "manta.key_path", "manta.key_id", "manta.timeout", "manta.http_transport"})
    public void beforeClass(@Optional String mantaUrl,
                            @Optional String mantaUser,
                            @Optional String mantaKeyPath,
                            @Optional String mantaKeyId,
                            @Optional Integer mantaTimeout,
                            @Optional String mantaHttpTransport)
            throws IOException, MantaCryptoException {

        // Let TestNG configuration take precedence over environment variables
        ConfigContext config = new IntegrationTestConfigContext(
                mantaUrl, mantaUser, mantaKeyPath, mantaKeyId, mantaTimeout,
                mantaHttpTransport);

        mantaClient = new MantaClient(config);
        testPathPrefix = String.format("/%s/stor/%s/",
                config.getMantaHomeDirectory(), UUID.randomUUID());
        mantaClient.putDirectory(testPathPrefix, null);
    }


    @AfterClass
    public void afterClass() throws IOException, MantaCryptoException {
        if (mantaClient != null) {
            mantaClient.deleteRecursive(testPathPrefix);
            mantaClient.closeWithWarning();
        }
    }


    @Test
    public final void seekableByteSize() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final long expectedSize = TEST_DATA.getBytes().length;

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            Assert.assertEquals(channel.size(), expectedSize,
                    "Size was not equal to uploaded test data");
        }
    }


    @Test
    public final void getAllSeekableBytes() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            String actual = new String(readAllBytes(channel));
            Assert.assertEquals(actual, TEST_DATA, "Couldn't read the same bytes as written");
        }
    }


    @Test
    public final void getAllSeekableBytesAtPosition() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final int position = 5;
        final String expected = TEST_DATA.substring(position);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path, position)) {
            String actual = new String(readAllBytes(channel));
            Assert.assertEquals(actual, expected, "Couldn't read the same bytes as written");
        }
    }

    @Test
    public final void readFromDifferentPositions() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            ByteBuffer first5Bytes = ByteBuffer.allocate(5);
            channel.read(first5Bytes);
            String firstPos = new String(first5Bytes.array());
            Assert.assertEquals(firstPos, TEST_DATA.substring(0, 5),
                    "Couldn't read the same bytes as written");

            try (SeekableByteChannel channel2 = channel.position(7L)) {
                ByteBuffer seventhTo12thBytes = ByteBuffer.allocate(5);
                channel2.read(seventhTo12thBytes);
                String secondPos = new String(seventhTo12thBytes.array());
                Assert.assertEquals(secondPos, TEST_DATA.substring(7, 12),
                        "Couldn't read the same bytes as written");
            }
        }
    }


    @Test
    public final void readAllSeekableBytesFromPositionAsInputStream() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final int position = 5;

        try (MantaSeekableByteChannel channel = mantaClient.getSeekableByteChannel(path, position)) {
            final String expected = TEST_DATA.substring(position);

            Assert.assertEquals(MantaUtils.inputStreamToString(channel),
                    expected, "Couldn't read the same bytes as written");

            final int secondPosition = 7;
            final String secondExpected = TEST_DATA.substring(secondPosition);
            try (MantaSeekableByteChannel channel2 = (MantaSeekableByteChannel)channel.position(secondPosition)) {

                Assert.assertEquals(MantaUtils.inputStreamToString(channel2),
                        secondExpected, "Couldn't read the same bytes as written");
            }
        }
    }

    @Test
    public final void skipUsingInputStream() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (MantaSeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            final String expected = TEST_DATA.substring(5);
            Assert.assertEquals(channel.skip(5), 5L, "Didn't skip the expected number of bytes");
            Assert.assertEquals(channel.position(), 5L, "Position didn't update properly");

            Assert.assertEquals(MantaUtils.inputStreamToString(channel),
                    expected, "Couldn't read the same bytes as written");

            Assert.assertEquals(channel.position(), TEST_DATA.length(),
                    "Position didn't update properly");
        }
    }

    @Test(expectedExceptions = ClosedChannelException.class)
    public final void closeAndAttemptToRead() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[2]);
            channel.read(buffer);

            channel.close();

            channel.read(buffer);
        }
    }


    @Test(expectedExceptions = ClosedChannelException.class)
    public final void closeAndAttemptToSize() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[2]);
            channel.read(buffer);
            channel.size();

            channel.close();

            channel.size();
        }
    }


    @Test(expectedExceptions = NonWritableChannelException.class)
    public final void attemptToWrite() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path)) {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[2]);
            channel.write(buffer);
        }
    }


    @Test( groups = { "seekable" })
    public final void getFromForwardPosition() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final int positionIndex = 4;
        final String expectedPosition1 = TEST_DATA.substring(positionIndex);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path);
             SeekableByteChannel position1 = channel.position(positionIndex)) {

            String actual = new String(readAllBytes(position1));
            Assert.assertEquals(actual, expectedPosition1,
                    "Couldn't read the same bytes as written to specified position");
        }
    }


    @Test( groups = { "seekable" } )
    public final void getFromBaseChannelThenForwardPosition() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final int positionIndex = 4;
        final String expectedPosition1 = TEST_DATA.substring(positionIndex);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path);
             SeekableByteChannel position1 = channel.position(positionIndex)) {

            String actual = new String(readAllBytes(channel));
            Assert.assertEquals(actual, TEST_DATA, "Couldn't read the same bytes as written");

            String positionActual = new String(readAllBytes(position1));
            Assert.assertEquals(positionActual, expectedPosition1,
                    "Couldn't read the same bytes as written to specified position");
        }
    }


    @Test( groups = { "seekable" } )
    public final void getFromForwardPositionThenBackwardPosition() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        final int position1Index = 4;
        final String expectedPosition1 = TEST_DATA.substring(position1Index);

        final int position2Index = 2;
        final String expectedPosition2 = TEST_DATA.substring(position2Index);

        try (SeekableByteChannel channel = mantaClient.getSeekableByteChannel(path);
             SeekableByteChannel position1 = channel.position(position1Index);
             SeekableByteChannel position2 = channel.position(position2Index)) {

            String actual = new String(readAllBytes(channel));
            Assert.assertEquals(actual, TEST_DATA, "Couldn't read the same bytes as written");

            String position1Actual = new String(readAllBytes(position1));
            Assert.assertEquals(position1Actual, expectedPosition1,
                    "Couldn't read the same bytes as written to specified position");

            String position2Actual = new String(readAllBytes(position2));
            Assert.assertEquals(position2Actual, expectedPosition2,
                    "Couldn't read the same bytes as written to specified position");
        }
    }


    public static byte[] readAllBytes(SeekableByteChannel sbc) throws IOException {
        try (InputStream in = Channels.newInputStream(sbc);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            IOUtils.copy(in, out);
            return out.toByteArray();
        }
    }
}
