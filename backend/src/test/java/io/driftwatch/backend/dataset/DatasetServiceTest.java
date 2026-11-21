package io.driftwatch.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.model.ModelNotFoundException;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dataset storage against real PostgreSQL and a real (temporary) file store. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatasetServiceTest {

  private static final byte[] CSV =
      "age,region\n31,north\n45,south\n".getBytes(StandardCharsets.UTF_8);

  @Autowired private DatasetService datasets;
  @Autowired private ModelService models;
  @Autowired private ContentAddressedFileStore files;
  @Autowired private DatasetProperties properties;
  @Autowired private JdbcTemplate jdbc;

  private UUID modelId;

  @BeforeEach
  void freshModel() {
    deleteAll();
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
  }

  /** These tests commit; leave the shared database empty for other test classes. */
  @AfterEach
  void deleteAll() {
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM monitored_model");
  }

  private static InputStream stream(byte[] data) {
    return new ByteArrayInputStream(data);
  }

  private int datasetRows() {
    return jdbc.queryForObject("SELECT count(*) FROM dataset", Integer.class);
  }

  @Test
  void storesFileAndRecordsTheUpload() throws Exception {
    Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    Dataset dataset = datasets.store(modelId, DatasetRole.REFERENCE, "reference.csv", stream(CSV));

    assertThat(dataset.getId().version()).isEqualTo(7);
    assertThat(dataset.getModelId()).isEqualTo(modelId);
    assertThat(dataset.getRole()).isEqualTo(DatasetRole.REFERENCE);
    assertThat(dataset.getSha256()).hasSize(64);
    assertThat(dataset.getByteSize()).isEqualTo(CSV.length);
    assertThat(dataset.getOriginalFilename()).isEqualTo("reference.csv");
    assertThat(dataset.getCreatedAt()).isAfterOrEqualTo(before);
    try (InputStream in = datasets.open(dataset)) {
      assertThat(in.readAllBytes()).isEqualTo(CSV);
    }
    assertThat(datasetRows()).isEqualTo(1);
  }

  @Test
  void fileStoreLivesUnderTheConfiguredTestDirectory() {
    // Guards against tests writing into the working tree.
    assertThat(properties.storageRoot().toString())
        .startsWith(System.getProperty("java.io.tmpdir"))
        .contains("driftwatch-test-");
  }

  @Test
  void sameContentUploadedTwiceGivesTwoRecordsAndOneFile() throws Exception {
    Dataset first = datasets.store(modelId, DatasetRole.REFERENCE, "a.csv", stream(CSV));
    Dataset second = datasets.store(modelId, DatasetRole.CURRENT, "b.csv", stream(CSV));

    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(second.getSha256()).isEqualTo(first.getSha256());
    assertThat(datasets.forModel(modelId))
        .extracting(Dataset::getId)
        .containsExactly(first.getId(), second.getId());
    assertThat(files.contains(first.getSha256())).isTrue();
  }

  @Test
  void unknownModelIsRejectedBeforeAnythingIsStored() throws Exception {
    UUID unknown = UUID.randomUUID();
    byte[] unique = "x\n1\n".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> datasets.store(unknown, DatasetRole.REFERENCE, null, stream(unique)))
        .isInstanceOf(ModelNotFoundException.class);
    assertThat(datasetRows()).isZero();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unique));
    assertThat(files.contains(hash)).isFalse();
  }

  @Test
  void oversizedUploadIsRejectedWithoutARecord() {
    byte[] tooLarge = new byte[(int) properties.maxUploadSize().toBytes() + 1];
    tooLarge[0] = 'a';

    assertThatThrownBy(
            () -> datasets.store(modelId, DatasetRole.CURRENT, "big.csv", stream(tooLarge)))
        .isInstanceOf(UploadTooLargeException.class);
    assertThat(datasetRows()).isZero();
  }

  @Test
  void emptyUploadIsRejectedWithoutARecord() {
    assertThatThrownBy(
            () -> datasets.store(modelId, DatasetRole.CURRENT, "empty.csv", stream(new byte[0])))
        .isInstanceOf(EmptyUploadException.class);
    assertThat(datasetRows()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "bad\nname.csv", "tab\tname.csv"})
  void invalidFileNamesAreRejected(String filename) {
    assertThatThrownBy(() -> datasets.store(modelId, DatasetRole.CURRENT, filename, stream(CSV)))
        .isInstanceOf(InvalidUploadException.class);
    assertThat(datasetRows()).isZero();
  }

  @Test
  void fileNameLengthLimit() throws Exception {
    datasets.store(modelId, DatasetRole.CURRENT, "f".repeat(255), stream(CSV));

    assertThatThrownBy(
            () -> datasets.store(modelId, DatasetRole.CURRENT, "f".repeat(256), stream(CSV)))
        .isInstanceOf(InvalidUploadException.class);
  }

  @Test
  void fileNameIsOptional() throws Exception {
    assertThat(
            datasets.store(modelId, DatasetRole.CURRENT, null, stream(CSV)).getOriginalFilename())
        .isNull();
  }

  @Test
  void fileNameIsMetadataNotAPath() throws Exception {
    Dataset dataset = datasets.store(modelId, DatasetRole.CURRENT, "../../etc/passwd", stream(CSV));

    assertThat(dataset.getOriginalFilename()).isEqualTo("../../etc/passwd");
    // Every stored file is named by its hash, so no client-supplied name was used as a path.
    try (Stream<Path> paths = Files.walk(properties.storageRoot())) {
      assertThat(paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
          .isNotEmpty()
          .allMatch(name -> name.matches("[0-9a-f]{64}") || name.endsWith(".part"));
    }
  }
}
