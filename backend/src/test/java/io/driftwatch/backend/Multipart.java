package io.driftwatch.backend;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Builds multipart/form-data request bodies for HTTP tests. */
public final class Multipart {

  private final String boundary = "test-" + UUID.randomUUID();
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();

  public Multipart file(String name, String filename, String contentType, byte[] content) {
    write("--" + boundary + "\r\n");
    write(
        "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
    write("Content-Type: " + contentType + "\r\n\r\n");
    body.writeBytes(content);
    write("\r\n");
    return this;
  }

  public Multipart field(String name, String value) {
    write("--" + boundary + "\r\n");
    write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
    write(value + "\r\n");
    return this;
  }

  public String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }

  public HttpRequest.BodyPublisher publisher() {
    ByteArrayOutputStream complete = new ByteArrayOutputStream();
    complete.writeBytes(body.toByteArray());
    complete.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
  }

  private void write(String text) {
    body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
  }
}
