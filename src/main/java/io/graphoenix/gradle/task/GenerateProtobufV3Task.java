package io.graphoenix.gradle.task;

import io.graphoenix.core.config.PackageConfig;
import io.graphoenix.core.handler.DocumentBuilder;
import io.graphoenix.core.handler.GraphQLConfigRegister;
import io.graphoenix.protobuf.handler.ProtobufFileBuilder;
import io.nozdormu.spi.context.BeanContext;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

public class GenerateProtobufV3Task extends BaseTask {

    private static final Logger logger = LoggerFactory.getLogger(GenerateProtobufV3Task.class);

    private final PackageConfig packageConfig = BeanContext.get(PackageConfig.class);
    private final GraphQLConfigRegister configRegister = BeanContext.get(GraphQLConfigRegister.class);
    private final DocumentBuilder documentBuilder = BeanContext.get(DocumentBuilder.class);
    private final ProtobufFileBuilder protobufFileBuilder = BeanContext.get(ProtobufFileBuilder.class);

    @TaskAction
    public void generateProtobufV3Task() {
        init();
        SourceSet sourceSet = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String javaPath = sourceSet.getJava().getSourceDirectories().filter(file -> file.getPath().contains(MAIN_JAVA_PATH)).getAsPath();
        Path protoPath = Path.of(javaPath).getParent().resolve("proto").resolve(packageConfig.getPackageName().replaceAll("\\.", Matcher.quoteReplacement(File.separator)));
        try {
            configRegister.registerPackage(createClassLoader());
            documentBuilder.build();
            registerInvoke();
            documentBuilder.buildInvoker();
            if (Files.notExists(protoPath)) {
                Files.createDirectories(protoPath);
            }
            Set<Map.Entry<String, String>> entries = protobufFileBuilder.buildProto3().entrySet();
            for (Map.Entry<String, String> entry : entries) {
                Files.writeString(
                        protoPath.resolve(entry.getKey() + ".proto"),
                        entry.getValue()
                );
            }
        } catch (IOException | URISyntaxException e) {
            logger.error(e.getMessage(), e);
            throw new TaskExecutionException(this, e);
        }
    }
}
