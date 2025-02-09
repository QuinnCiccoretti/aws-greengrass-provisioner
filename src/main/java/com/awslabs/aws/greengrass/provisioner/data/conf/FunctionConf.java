package com.awslabs.aws.greengrass.provisioner.data.conf;

import com.awslabs.aws.greengrass.provisioner.data.Language;
import com.awslabs.aws.greengrass.provisioner.data.resources.LocalDeviceResource;
import com.awslabs.aws.greengrass.provisioner.data.resources.LocalS3Resource;
import com.awslabs.aws.greengrass.provisioner.data.resources.LocalSageMakerResource;
import com.awslabs.aws.greengrass.provisioner.data.resources.LocalVolumeResource;
import org.immutables.value.Value;
import software.amazon.awssdk.services.greengrass.model.EncodingType;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Value.Modifiable
public abstract class FunctionConf {
    public abstract Language getLanguage();

    public abstract EncodingType getEncodingType();

    public abstract Path getBuildDirectory();

    public abstract String getGroupName();

    public abstract String getFunctionName();

    public abstract String getHandlerName();

    public abstract String getAliasName();

    public abstract int getMemorySizeInKb();

    public abstract boolean isPinned();

    public abstract int getTimeoutInSeconds();

    public abstract List<String> getFromCloudSubscriptions();

    public abstract List<String> getToCloudSubscriptions();

    public abstract List<String> getOutputTopics();

    public abstract List<String> getInputTopics();

    public abstract List<String> getConnectedShadows();

    public abstract List<LocalDeviceResource> getLocalDeviceResources();

    public abstract List<LocalVolumeResource> getLocalVolumeResources();

    public abstract List<LocalS3Resource> getLocalS3Resources();

    public abstract List<LocalSageMakerResource> getLocalSageMakerResources();

    public abstract boolean isAccessSysFs();

    public abstract boolean isGreengrassContainer();

    public abstract int getUid();

    public abstract int getGid();

    public abstract Map<String, String> getEnvironmentVariables();

    public abstract Optional<File> getCfTemplate();
}
