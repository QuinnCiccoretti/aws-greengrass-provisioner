package com.awslabs.aws.greengrass.provisioner.implementations.builders;

import com.awslabs.aws.greengrass.provisioner.data.conf.FunctionConf;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.GradleBuilder;
import io.vavr.control.Try;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public class BasicGradleBuilder implements GradleBuilder {
    private static final String BUILD_GRADLE = "build.gradle";

    @Inject
    public BasicGradleBuilder() {
    }

    @Override
    public String getArchivePath(FunctionConf functionConf) {
        // The gradle build output is expected in the /build/libs directory in an artifact that ends with "-1.0-SNAPSHOT-all.jar"
        return functionConf.getBuildDirectory().toString() + "/build/libs/" + functionConf.getFunctionName() + "-1.0-SNAPSHOT-all.jar";
    }

    @Override
    public String getGradleBuildPath(FunctionConf functionConf) {
        return functionConf.getBuildDirectory().toString();
    }

    @Override
    public boolean isGradleFunction(FunctionConf functionConf) {
        if (new File(getGradleBuildPath(functionConf) + "/" + BUILD_GRADLE).exists()) {
            // Found build.gradle in the expected location, assume this is a gradle function
            return true;
        }

        return false;
    }

    @Override
    public boolean isGradleFunction(Path path) {
        if (path.resolve(BUILD_GRADLE).toFile().exists()) {
            return true;
        }

        return false;
    }

    @Override
    public void buildJavaFunctionIfNecessary(FunctionConf functionConf) {
        runGradle(Optional.of(new File(getGradleBuildPath(functionConf))), Optional.ofNullable(functionConf.getFunctionName()));
    }

    @Override
    public void runGradle(Optional<File> gradleBuildPath, Optional<String> functionName) {
        if (!gradleBuildPath.isPresent()) {
            throw new RuntimeException("gradle build path is not present.  This is a bug.");
        }

        // Guidance from: https://discuss.gradle.org/t/how-to-execute-a-gradle-task-from-java-code/7421
        Try.withResources(() -> getProjectConnection(gradleBuildPath))
                .of(this::runBuild)
                .get();
    }

    private Void runBuild(ProjectConnection projectConnection) {
        // Build with gradle and send the output to stdout
        BuildLauncher build = projectConnection.newBuild();
        build.forTasks("build");
        build.setStandardOutput(System.out);
        build.run();

        return null;
    }

    private ProjectConnection getProjectConnection(Optional<File> gradleBuildPath) {
        return GradleConnector.newConnector()
                .forProjectDirectory(gradleBuildPath.get())
                .connect();
    }

    @Override
    public Optional<String> verifyHandlerExists(FunctionConf functionConf) {
        throw new RuntimeException("Not implemented yet");
    }
}
