package com.awslabs.aws.greengrass.provisioner.implementations.builders;

import com.awslabs.aws.greengrass.provisioner.data.SDK;
import com.awslabs.aws.greengrass.provisioner.data.conf.FunctionConf;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.NodeBuilder;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.IoHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.LoggingHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.ProcessHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.ResourceHelper;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.zip.ZipUtil;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BasicNodeBuilder implements NodeBuilder {
    private final Logger log = LoggerFactory.getLogger(BasicNodeBuilder.class);
    @Inject
    ProcessHelper processHelper;
    @Inject
    LoggingHelper loggingHelper;
    @Inject
    ResourceHelper resourceHelper;
    @Inject
    IoHelper ioHelper;

    @Inject
    public BasicNodeBuilder() {
    }

    @Override
    public Optional<SDK> getSdk() {
        return Optional.of(SDK.NODEJS);
    }

    @Override
    public String getSdkDestinationPath() {
        return ".";
    }

    @Override
    public void buildFunctionIfNecessary(FunctionConf functionConf) {
        loggingHelper.logInfoWithName(log, functionConf.getFunctionName(), "Copying Greengrass SDK");
        copySdk(log, functionConf, resourceHelper, ioHelper);

        if (hasDependencies(functionConf.getBuildDirectory())) {
            loggingHelper.logInfoWithName(log, functionConf.getFunctionName(), "Installing Node dependencies");

            // Install all of the dependencies for this function
            installDependencies(functionConf);
        }

        loggingHelper.logInfoWithName(log, functionConf.getFunctionName(), "Packaging function for AWS Lambda");

        File tempFile = Try.of(() -> ioHelper.getTempFile("node-lambda-build", "zip")).get();

        // Create the deployment package
        ZipUtil.pack(new File(functionConf.getBuildDirectory().toString()), tempFile);

        moveDeploymentPackage(functionConf, tempFile);
    }

    @Override
    public boolean hasDependencies(Path buildDirectory) {
        return buildDirectory.resolve("package.json").toFile().exists();
    }

    private void installDependencies(FunctionConf functionConf) {
        loggingHelper.logInfoWithName(log, functionConf.getFunctionName(), "Retrieving Node dependencies");
        List<String> programAndArguments = new ArrayList<>();
        programAndArguments.add("npm");
        programAndArguments.add("install");

        ProcessBuilder processBuilder = processHelper.getProcessBuilder(programAndArguments);
        processBuilder.directory(new File(functionConf.getBuildDirectory().toString()));

        List<String> stdoutStrings = new ArrayList<>();
        List<String> stderrStrings = new ArrayList<>();

        Optional<Integer> exitVal = processHelper.getOutputFromProcess(log, processBuilder, true, Optional.of(stdoutStrings::add), Optional.of(stderrStrings::add));

        if (!exitVal.isPresent() || exitVal.get() != 0) {
            log.error("Failed to install Node dependency.  Make sure Node and npm are installed and on your path.");
            System.exit(1);
        }
    }

    @Override
    public Optional<String> verifyHandlerExists(FunctionConf functionConf) {
        // TODO: Implement me!
        return Optional.empty();
    }
}
