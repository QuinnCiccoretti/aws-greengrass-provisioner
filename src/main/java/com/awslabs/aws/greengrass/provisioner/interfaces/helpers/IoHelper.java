package com.awslabs.aws.greengrass.provisioner.interfaces.helpers;

import com.awslabs.aws.greengrass.provisioner.data.ImmutableKeysAndCertificate;
import com.awslabs.aws.greengrass.provisioner.data.KeysAndCertificate;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.oblac.nomen.Nomen;
import io.vavr.control.Try;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

public interface IoHelper {
    String TEMP_DIRECTORY = "/tmp/";

    default void writeFile(File file, byte[] contents) {
        if ((isRunningInLambda()) && (!file.getAbsolutePath().startsWith(TEMP_DIRECTORY))) {
            // If we are running in Lambda we can only put files in the temp directory
            file = new File(TEMP_DIRECTORY + file.getAbsolutePath());
        }

        final File finalFile = file;

        createDirectoryIfNecessary(file.getParentFile().getPath());

        Try.withResources(() -> new FileOutputStream(finalFile))
                .of(fileOutputStream -> writeFile(fileOutputStream, contents))
                .get();

        makeWritable(file);
    }

    default Void writeFile(FileOutputStream fileOutputStream, byte[] contents) throws IOException {
        fileOutputStream.write(contents);

        return null;
    }

    default void writeFile(String filename, byte[] contents) {
        writeFile(new File(filename), contents);
    }

    default String getUuid() {
        return UUID.randomUUID().toString();
    }

    default String getRandomName() {
        return Nomen.est().withSeparator("_").withSpace("_").adjective().noun().get();
    }

    default byte[] readFile(String filename) {
        return Try.of(() -> Files.readAllBytes(Paths.get(filename))).get();
    }

    default String readFileAsString(File file) {
        byte[] data = readFile(file);

        return new String(data);
    }

    default byte[] readFile(File file) {
        return Try.of(() -> Files.readAllBytes(file.toPath())).get();
    }

    default byte[] readFile(URL url) {
        return Try.withResources(url::openStream)
                .of(this::getByteArrayFromInputStream)
                .get();
    }

    default byte[] getByteArrayFromInputStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        int len = inputStream.read(buffer);

        while (len != -1) {
            baos.write(buffer, 0, len);
            len = inputStream.read(buffer);
        }

        return baos.toByteArray();
    }

    default String download(String url) {
        return Try.of(() -> new Scanner(new URL(url).openStream(), "UTF-8").useDelimiter("\\A").next())
                .get();
    }

    default void makeExecutable(String filename) {
        File file = new File(filename);
        // When using Docker the executable flag gets set as root only, setting the second parameter to false makes it executable by all
        file.setExecutable(true, false);
    }

    default void makeReadable(String filename) {
        File file = new File(filename);
        // When using Docker the read flag gets set as root only, setting the second parameter to false makes it readable by all
        file.setReadable(true, false);
    }

    default void makeWritable(File file) {
        // When using Docker the write gets set as root only, setting the second parameter to false makes it writable by all
        file.setWritable(true, false);
    }

    default void makeWritable(String filename) {
        File file = new File(filename);
        makeWritable(file);
    }

    default void createDirectoryIfNecessary(String path) {
        new File(path).mkdirs();
        makeWritable(path);
    }

    default boolean exists(String path) {
        return new File(path).exists();
    }

    default String serializeObject(Object object, JsonHelper jsonHelper) {
        return jsonHelper.toJson(object);
    }

    default Object deserializeObject(byte[] bytes, JsonHelper jsonHelper) {
        return Try.of(() -> (Object) jsonHelper.fromJson(KeysAndCertificate.class, bytes))
                .get();
    }

    default String serializeKeys(CreateKeysAndCertificateResponse createKeysAndCertificateResponse, JsonHelper jsonHelper) {
        KeysAndCertificate keysAndCertificate = ImmutableKeysAndCertificate.builder()
                .certificateArn(createKeysAndCertificateResponse.certificateArn())
                .certificateId(createKeysAndCertificateResponse.certificateId())
                .certificatePem(createKeysAndCertificateResponse.certificatePem())
                .keyPair(createKeysAndCertificateResponse.keyPair())
                .build();

        return serializeObject(keysAndCertificate, jsonHelper);
    }

    default KeysAndCertificate deserializeKeys(byte[] readFile, JsonHelper jsonHelper) {
        Object object = deserializeObject(readFile, jsonHelper);

        if (object instanceof KeysAndCertificate) {
            return (KeysAndCertificate) object;
        }

        if (object instanceof CreateKeysAndCertificateResponse) {
            return KeysAndCertificate.from((CreateKeysAndCertificateResponse) object);
        }

        throw new RuntimeException("Couldn't deserialize keys.  This is a bug.");
    }

    default Optional<InputStream> extractTar(String tarFile, String filenameToExtract) {
        try (final TarArchiveInputStream tarIn = new TarArchiveInputStream(new FileInputStream(new File(tarFile)))) {
            return getInputStreamFromTar(tarIn, filenameToExtract);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default Optional<InputStream> extractTarGz(String tarGzFile, String filenameToExtract) {
        try (final TarArchiveInputStream tarIn = new TarArchiveInputStream(new GZIPInputStream(new FileInputStream(new File(tarGzFile))))) {
            return getInputStreamFromTar(tarIn, filenameToExtract);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default Optional<InputStream> getInputStreamFromTar(TarArchiveInputStream tarIn, String filenameToExtract) throws IOException {
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();

        while (tarEntry != null) {
            String currentFileName = tarEntry.getName();

            if (!currentFileName.endsWith(filenameToExtract)) {
                tarEntry = tarIn.getNextTarEntry();
                continue;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            int length;

            byte[] buffer = new byte[16384];

            while ((length = tarIn.read(buffer)) != -1) {
                baos.write(buffer, 0, length);
            }

            return Optional.of(new ByteArrayInputStream(baos.toByteArray()));
        }

        return Optional.empty();
    }

    /**
     * Returns a temp file that is deleted when the JVM exits
     *
     * @param prefix
     * @param suffix
     * @return
     */
    default File getTempFile(String prefix, String suffix) throws IOException {
        File innerTempFile = File.createTempFile(prefix, suffix);
        innerTempFile.deleteOnExit();
        return innerTempFile;
    }

    /**
     * Sleep and convert InterruptedExceptions to RuntimeExceptions
     *
     * @param milliseconds
     */
    default void sleep(int milliseconds) {
        Try.of(() -> sleepWithCheckedException(milliseconds))
                .get();
    }

    default Void sleepWithCheckedException(int milliseconds) throws InterruptedException {
        Thread.sleep(milliseconds);
        return null;
    }

    boolean isRunningInDocker();

    boolean isRunningInLambda();

    List<String> getPrivateKeyFilesForSsh() throws IOException;

    Void extractZip(File zipFile, Path destinationPath, Function<String, String> filenameTrimmer) throws IOException;

    Void extractZip(InputStream zipInputStream, Path destinationPath, Function<String, String> filenameTrimmer) throws IOException;

    Void download(String url, File file, Optional<String> optionalReferer) throws IOException;

    JSch getJschWithPrivateKeysLoaded();

    Callable<Session> getSshSessionTask(String hostname,
                                        String user,
                                        String connectedMessage,
                                        String timeoutMessage,
                                        String refusedMessage,
                                        String errorMessage);

    String runCommand(Session session, String command) throws JSchException, IOException;

    String runCommand(Session session, String command, Optional<Consumer<String>> optionalStringConsumer) throws JSchException, IOException;

    Void sendFile(Session session, String localFilename, String remoteFilename) throws JSchException, IOException;
}

