package io.driftwatch.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentAddressedFileStoreTest {

  private static final byte[] CSV = "a,b\n1,2\n3,4\n".getBytes(StandardCharsets.UTF_8);

  @TempDir Path root;

  private ContentAddressedFileStore store(long maxBytes) {
    return new ContentAddressedFileStore(root, maxBytes);
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private static InputStream stream(byte[] data) {
    return new ByteArrayInputStream(data);
  }

  /** Regular files in the store, excluding the temporary directory. */
  private List<Path> storedFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(root.resolve(".tmp")))
          .toList();
    }
  }

  private List<Path> temporaryFiles() throws IOException {
    Path tmp = root.resolve(".tmp");
    if (!Files.exists(tmp)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.list(tmp)) {
      return paths.toList();
    }
  }

  @Test
  void storesUnderTheSha256OfTheContent() throws Exception {
    ContentAddressedFileStore files = store(1_000);

    ContentAddressedFileStore.StoredFile stored = files.store(stream(CSV));

    String expected = sha256(CSV);
    assertThat(stored.sha256()).isEqualTo(expected);
    assertThat(stored.byteSize()).isEqualTo(CSV.length);
    assertThat(stored.alreadyStored()).isFalse();
    assertThat(storedFiles())
        .containsExactly(
            root.resolve(expected.substring(0, 2))
                .resolve(expected.substring(2, 4))
                .resolve(expected));
    try (InputStream in = files.open(expected)) {
      assertThat(in.readAllBytes()).isEqualTo(CSV);
    }
  }

  @Test
  void identicalContentIsStoredOnce() throws Exception {
    ContentAddressedFileStore files = store(1_000);
    ContentAddressedFileStore.StoredFile first = files.store(stream(CSV));

    ContentAddressedFileStore.StoredFile second = files.store(stream(CSV.clone()));

    assertThat(second.sha256()).isEqualTo(first.sha256());
    assertThat(second.alreadyStored()).isTrue();
    assertThat(storedFiles()).hasSize(1);
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void differentContentIsStoredSeparately() throws Exception {
    ContentAddressedFileStore files = store(1_000);

    String a = files.store(stream(CSV)).sha256();
    String b = files.store(stream("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8))).sha256();

    assertThat(a).isNotEqualTo(b);
    assertThat(storedFiles()).hasSize(2);
  }

  @Test
  void contentExactlyAtTheLimitIsAccepted() throws Exception {
    ContentAddressedFileStore files = store(CSV.length);

    assertThat(files.store(stream(CSV)).byteSize()).isEqualTo(CSV.length);
  }

  @Test
  void contentOverTheLimitIsRejectedAndNothingIsKept() throws Exception {
    ContentAddressedFileStore files = store(CSV.length - 1);

    assertThatThrownBy(() -> files.store(stream(CSV)))
        .isInstanceOf(UploadTooLargeException.class)
        .hasMessage("Upload exceeds the limit of " + (CSV.length - 1) + " bytes");
    assertThat(storedFiles()).isEmpty();
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void oversizedStreamIsCutOffAtTheLimitNotReadToTheEnd() throws Exception {
    // An "endless" stream: if the store read everything first, this test would never finish.
    InputStream endless =
        new InputStream() {
          @Override
          public int read() {
            return 'x';
          }

          @Override
          public int read(byte[] b, int off, int len) {
            Arrays.fill(b, off, off + len, (byte) 'x');
            return len;
          }
        };

    assertThatThrownBy(() -> store(1_000_000).store(endless))
        .isInstanceOf(UploadTooLargeException.class);
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void emptyContentIsRejected() throws Exception {
    assertThatThrownBy(() -> store(1_000).store(stream(new byte[0])))
        .isInstanceOf(EmptyUploadException.class);
    assertThat(storedFiles()).isEmpty();
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void largeContentStreamsCorrectly() throws Exception {
    byte[] data = new byte[20 * 1024 * 1024 + 123]; // not a multiple of the buffer size
    new Random(42).nextBytes(data);

    ContentAddressedFileStore.StoredFile stored = store(data.length).store(stream(data));

    assertThat(stored.sha256()).isEqualTo(sha256(data));
    assertThat(stored.byteSize()).isEqualTo(data.length);
  }

  @Test
  void storedFilesAreReadOnly() throws Exception {
    ContentAddressedFileStore files = store(1_000);

    Path stored = files.pathOf(files.store(stream(CSV)).sha256());

    assertThat(Files.isWritable(stored)).isFalse();
    if (stored.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored)))
          .isEqualTo("r--r-----");
    }
  }

  @Test
  void concurrentStoresOfTheSameContentProduceOneFile() throws Exception {
    ContentAddressedFileStore files = store(1_000);
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> hashes = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        hashes.add(
            pool.submit(() -> {
              start.await();
              return files.store(stream(CSV)).sha256();
            }));
      }
      start.countDown();
      for (Future<String> hash : hashes) {
        assertThat(hash.get()).isEqualTo(sha256(CSV));
      }
    }
    assertThat(storedFiles()).hasSize(1);
    assertThat(temporaryFiles()).isEmpty();
    try (InputStream in = files.open(sha256(CSV))) {
      assertThat(in.readAllBytes()).isEqualTo(CSV);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../../../../etc/passwd",
        "ABCDEF0000000000000000000000000000000000000000000000000000000000",
        "abc",
        ""
      })
  void onlyLowerCaseSha256HexIsAcceptedAsAnAddress(String address) {
    ContentAddressedFileStore files = store(1_000);

    assertThatThrownBy(() -> files.open(address)).isInstanceOf(IllegalArgumentException.class);
    assertThat(files.contains("0".repeat(64))).isFalse();
  }

  @Test
  void openingAMissingFileFails() {
    assertThatThrownBy(() -> store(1_000).open("0".repeat(64)))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void limitMustBePositive() {
    assertThatThrownBy(() -> store(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
