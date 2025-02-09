package com.awslabs.aws.greengrass.provisioner.implementations.helpers;

import com.awslabs.aws.greengrass.provisioner.data.exceptions.SshRecoverableException;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.GlobalDefaultHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.IoHelper;
import com.jcraft.jsch.*;
import io.vavr.control.Try;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BasicIoHelper implements IoHelper {
    private final Logger log = LoggerFactory.getLogger(BasicIoHelper.class);
    @Inject
    GlobalDefaultHelper globalDefaultHelper;

    @Override
    public boolean isRunningInDocker() {
        String proc1CgroupContents = Try.of(() -> readFileAsString(new File("/proc/1/cgroup"))).getOrElse("");

        return proc1CgroupContents.contains(":/docker/");
    }

    @Override
    public boolean isRunningInLambda() {
        return System.getenv("LAMBDA_TASK_ROOT") != null;
    }

    @Override
    public List<String> getPrivateKeyFilesForSsh() throws IOException {
        Optional<String> optionalHomeDirectory = globalDefaultHelper.getHomeDirectory();

        if (!optionalHomeDirectory.isPresent()) {
            return new ArrayList<>();
        }

        Path sshDirectory = new File(String.join("/", optionalHomeDirectory.get(), ".ssh")).toPath();

        // Recursively get all of the files in the directory, only look at regular files, and make sure they look like private keys
        return Files.walk(sshDirectory)
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .filter(file -> readFileAsString(file).contains("BEGIN RSA PRIVATE KEY"))
                .map(File::toString)
                .collect(Collectors.toList());
    }

    @Override
    public Void extractZip(File zipFile, Path destinationPath, Function<String, String> filenameTrimmer) throws IOException {
        return extractZip(new FileInputStream(zipFile), destinationPath, filenameTrimmer);
    }

    @Override
    public Void extractZip(InputStream zipInputStream, Path destinationPath, Function<String, String> filenameTrimmer) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipInputStream);
        ZipEntry entry;

        // Create the base path if necessary
        new File(destinationPath.toString()).mkdirs();

        // Extract the entire SDK to where we are doing our build
        while ((entry = zis.getNextEntry()) != null) {
            String filename = entry.getName();
            filename = filenameTrimmer.apply(filename);
            String path = String.join("/", destinationPath.toString(), filename);

            if (entry.isDirectory()) {
                new File(path).mkdirs();
            } else {
                File newFile = new File(path);
                FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                int length;
                byte[] buffer = new byte[8192];

                while ((length = zis.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, length);
                }

                fileOutputStream.close();
            }
        }

        return null;
    }

    @Override
    public Void download(String url, File file, Optional<String> optionalReferer) throws IOException {
        // From: http://stackoverflow.com/a/921400
        URL website = new URL(url);
        URLConnection urlConnection = website.openConnection();
        optionalReferer.ifPresent(referer -> urlConnection.setRequestProperty("Referer", referer));
        InputStream inputStream = urlConnection.getInputStream();
        ReadableByteChannel rbc = Channels.newChannel(inputStream);
        FileOutputStream fos = new FileOutputStream(file);
        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);

        return null;
    }

    @Override
    public JSch getJschWithPrivateKeysLoaded() {
        JSch jsch = new JSch();

        List<String> privateKeyFiles = Try.of(() -> getPrivateKeyFilesForSsh()).get();

        for (String privateKeyFile : privateKeyFiles) {
            Try.of(() -> addIdentity(jsch, privateKeyFile))
                    .recover(JSchException.class, throwable -> logPrivateKeyIssueAndIgnore(privateKeyFile))
                    .get();
        }

        return jsch;
    }

    private Void logPrivateKeyIssueAndIgnore(String privateKeyFile) {
        log.error("Issue with private key file [" + privateKeyFile + "], skipping");
        return null;
    }

    private Void addIdentity(JSch jsch, String privateKeyFile) throws JSchException {
        jsch.addIdentity(privateKeyFile);
        return null;
    }

    @Override
    public Callable<Session> getSshSessionTask(String hostname,
                                               String user,
                                               String connectedMessage,
                                               String timeoutMessage,
                                               String refusedMessage,
                                               String errorMessage) {
        return getSshSessionCallable(hostname,
                user,
                getJschWithPrivateKeysLoaded(),
                connectedMessage,
                timeoutMessage,
                refusedMessage,
                errorMessage);
    }

    private Callable<Session> getSshSessionCallable(String hostname,
                                                    String user,
                                                    JSch jsch,
                                                    String connectedMessage,
                                                    String timeoutMessage,
                                                    String refusedMessage,
                                                    String errorMessage) {
        return () -> getSession(hostname, user, jsch, connectedMessage, timeoutMessage, refusedMessage, errorMessage);
    }

    private Session getSession(String hostname, String user, JSch jsch, String connectedMessage, String timeoutMessage, String refusedMessage, String errorMessage) {
        RetryPolicy<Session> sessionRetryPolicy = new RetryPolicy<Session>()
                .handle(SshRecoverableException.class)
                .withDelay(Duration.ofSeconds(10))
                .withMaxRetries(3)
                .onRetry(failure -> log.warn("Waiting for instance to become available [" + failure.getLastFailure().getMessage() + "]"))
                .onRetriesExceeded(failure -> log.error("Instance never became available [" + failure.getFailure().getMessage() + "]"));

        return Failsafe.with(sessionRetryPolicy)
                .get(() -> innerGetSession(hostname, user, jsch, connectedMessage, timeoutMessage, refusedMessage, errorMessage));
    }

    private Session innerGetSession(String hostname, String user, JSch jsch, String connectedMessage, String timeoutMessage, String refusedMessage, String errorMessage) throws JSchException {
        Session innerSession = jsch.getSession(user, hostname, 22);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        innerSession.setConfig(config);

        try {
            innerSession.connect(10000);
        } catch (JSchException e) {
            recoverFromConnectionIssueIfPossible(hostname, timeoutMessage, refusedMessage, errorMessage, e);
        }

        if (!connectedMessage.isEmpty()) {
            log.info(connectedMessage);
        }

        return innerSession;
    }

    private void recoverFromConnectionIssueIfPossible(String hostname, String timeoutMessage, String refusedMessage, String errorMessage, JSchException throwable) {
        String message = throwable.getMessage();

        if (message.contains("timeout")) {
            throw new SshRecoverableException(timeoutMessage);
        }

        if (message.contains("Connection refused")) {
            throw new SshRecoverableException(refusedMessage);
        }

        if (message.contains("Auth fail")) {
            throw new SshRecoverableException("Authentication error occurred, the SSH key may be missing or incorrect. Giving up.");
        }

        if (throwable.getCause() instanceof UnknownHostException) {
            throw new RuntimeException(String.format("Host [%s] could not be resolved, is the hostname correct?", hostname));
        }

        throw new SshRecoverableException(throwable.getMessage());
    }

    @Override
    public String runCommand(Session session, String command) throws JSchException, IOException {
        return runCommand(session, command, Optional.empty());
    }

    @Override
    public String runCommand(Session session, String command, Optional<Consumer<String>> optionalStringConsumer) throws JSchException, IOException {
        Channel channel = session.openChannel("exec");
        ((ChannelExec) channel).setCommand(command);

        StringBuilder stringBuilder = new StringBuilder();
        StringBuilder lineStringBuilder = new StringBuilder();

        try (InputStream commandOutput = channel.getInputStream()) {
            channel.connect();
            int readByte = commandOutput.read();

            while (readByte != 0xffffffff) {
                char character = (char) readByte;
                readByte = commandOutput.read();

                stringBuilder.append(character);

                if (character == '\r') {
                    // Throw away \r
                } else if (character == '\n') {
                    String line = lineStringBuilder.toString();
                    optionalStringConsumer.ifPresent(consumer -> consumer.accept(line));
                    lineStringBuilder = new StringBuilder();
                } else {
                    lineStringBuilder.append(character);
                }
            }
        } finally {
            channel.disconnect();
        }

        return stringBuilder.toString();
    }

    @Override
    public Void sendFile(Session session, String localFilename, String remoteFilename) throws JSchException, IOException {
        boolean preserveTimestamp = false;

        // exec 'scp -t rfile' remotely
        remoteFilename = remoteFilename.replace("'", "'\"'\"'");
        remoteFilename = "'" + remoteFilename + "'";

        String command = "scp " + (preserveTimestamp ? "-p" : "") + " -t " + remoteFilename;

        Channel channel = session.openChannel("exec");
        ((ChannelExec) channel).setCommand(command);

        // get I/O streams for remote scp
        try (OutputStream outputStream = channel.getOutputStream();
             InputStream inputStream = channel.getInputStream()) {
            channel.connect();

            if (checkAck(inputStream) != 0) {
                throw new RuntimeException("Bad acknowledgement while secure copying file, bailing out");
            }

            File localFile = new File(localFilename);

            if (preserveTimestamp) {
                command = "T " + (localFile.lastModified() / 1000) + " 0";
                // The access time should be sent here,
                // but it is not accessible with JavaAPI ;-<
                command += (" " + (localFile.lastModified() / 1000) + " 0\n");

                outputStream.write(command.getBytes());
                outputStream.flush();

                if (checkAck(inputStream) != 0) {
                    throw new RuntimeException("Failure when calling checkAck in sendFile [1]");
                }
            }

            // send "C0644 filesize filename", where filename should not include '/'
            long filesize = localFile.length();

            command = "C0644 " + filesize + " ";

            if (localFilename.lastIndexOf('/') > 0) {
                command += localFilename.substring(localFilename.lastIndexOf('/') + 1);
            } else {
                command += localFilename;
            }

            command += "\n";

            outputStream.write(command.getBytes());
            outputStream.flush();

            if (checkAck(inputStream) != 0) {
                throw new RuntimeException("Failure when calling checkAck in sendFile [2]");
            }

            byte[] bytes = new byte[1024];

            // send a content of localFilename
            try (FileInputStream fileInputStream = new FileInputStream(localFilename)) {
                while (true) {
                    int length = fileInputStream.read(bytes, 0, bytes.length);
                    if (length <= 0) break;
                    outputStream.write(bytes, 0, length); //out.flush();
                }
            }

            // send '\0'
            bytes[0] = 0;

            outputStream.write(bytes, 0, 1);
            outputStream.flush();

            if (checkAck(inputStream) != 0) {
                throw new RuntimeException("Failure when calling checkAck in sendFile [3]");
            }
        }

        channel.disconnect();

        return null;
    }

    private int checkAck(InputStream inputStream) throws IOException {
        int statusByte = inputStream.read();

        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1

        if (statusByte == 0) return statusByte;
        if (statusByte == -1) return statusByte;

        if (statusByte == 1 || statusByte == 2) {
            StringBuilder stringBuilder = new StringBuilder();

            int nextChar;

            do {
                nextChar = inputStream.read();
                stringBuilder.append((char) nextChar);
            }
            while (nextChar != '\n');

            if (statusByte == 1) { // error
                log.error(stringBuilder.toString());
            }

            if (statusByte == 2) { // fatal error
                log.error(stringBuilder.toString());
            }
        }

        return statusByte;
    }
}
