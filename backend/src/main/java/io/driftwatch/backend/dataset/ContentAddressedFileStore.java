package io.driftwatch.backend.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Stores files under their SHA-256 hash: {@code <root>/ab/cd/abcd…}.
 *
 * <ul>
 *   <li><b>Streaming:</b> the hash and size are computed while copying, so a file is never held in
 *       memory, and an upload over the limit is cut off as soon as it crosses it.
 *   <li><b>Atomic:</b> data is written to {@code <root>/.tmp} and moved into place in one step, so
 *       a reader never sees a partially written file. The temporary directory is inside the root,
 *       which keeps the move on one filesystem.
 *   <li><b>Deduplicated and immutable:</b> identical content is stored once and made read-only.
 *       Because the name is the hash of the content, a stored file can never need to change.
 * </ul>
 *
 * <p>If two uploads of the same content race, both may move their copy into place; the copies are
 * byte-identical, so either outcome is correct.
 */
public class ContentAddressedFileStore {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final int BUFFER_SIZE = 64 * 1024;

  private final Path root;
  private final Path tmpDir;
  private final long maxBytes;

  /** Result of {@link #store}. */
  public record StoredFile(String sha256, long byteSize, boolean alreadyStored) {}

  public ContentAddressedFileStore(Path root, long maxBytes) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be >= 1");
    }
    this.root = root.toAbsolutePath().normalize();
    this.tmpDir = this.root.resolve(".tmp");
    this.maxBytes = maxBytes;
  }

  public long maxBytes() {
    return maxBytes;
  }

  /**
   * Copies {@code content} into the store. The stream is read to the end (or to the limit) but not
   * closed; the caller owns it.
   *
   * @throws UploadTooLargeException if the content is larger than {@link #maxBytes()}
   * @throws EmptyUploadException if the content is empty
   */
  public StoredFile store(InputStream content) throws IOException {
    Files.createDirectories(tmpDir);
    Path temp = Files.createTempFile(tmpDir, "upload-", ".part");
    try {
      MessageDigest digest = sha256();
      long size = 0;
      try (OutputStream out = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = content.read(buffer)) != -1) {
          size += read;
          if (size > maxBytes) {
            throw new UploadTooLargeException(maxBytes);
          }
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
        }
      }
      if (size == 0) {
        throw new EmptyUploadException();
      }

      String hash = HexFormat.of().formatHex(digest.digest());
      Path target = pathOf(hash);
      if (Files.exists(target)) {
        // Files only appear in the store through an atomic move, so it is complete.
        return new StoredFile(hash, size, true);
      }
      Files.createDirectories(target.getParent());
      makeReadOnly(temp);
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException raced) {
        return new StoredFile(hash, size, true);
      }
      return new StoredFile(hash, size, false);
    } finally {
      // No-op after a successful move; removes partial data on any failure.
      Files.deleteIfExists(temp);
    }
  }

  /** Opens a stored file for reading. */
  public InputStream open(String sha256) throws IOException {
    return Files.newInputStream(pathOf(sha256));
  }

  /** A stored file as a Spring {@link Resource}, e.g. for a multipart upload. */
  public Resource resource(String sha256) {
    return new FileSystemResource(pathOf(sha256));
  }

  public boolean contains(String sha256) {
    return Files.isRegularFile(pathOf(sha256));
  }

  /**
   * Location of a hash in the store. Only lower-case 64-character hex is accepted, which also rules
   * out path traversal ({@code ../}) through a crafted hash.
   */
  Path pathOf(String sha256) {
    if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
      throw new IllegalArgumentException("not a lower-case hex SHA-256: " + sha256);
    }
    // Two levels of 256 directories keep any one directory small.
    return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
  }

  private static void makeReadOnly(Path file) throws IOException {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r-----"));
    } else {
      file.toFile().setReadOnly();
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }
}
