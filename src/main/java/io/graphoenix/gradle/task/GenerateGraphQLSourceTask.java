package io.graphoenix.gradle.task;

import io.graphoenix.core.config.GraphQLConfig;
import io.graphoenix.core.handler.DocumentBuilder;
import io.graphoenix.core.handler.GraphQLConfigRegister;
import io.graphoenix.java.builder.JavaFileBuilder;
import io.nozdormu.spi.context.BeanContext;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class GenerateGraphQLSourceTask extends BaseTask {

    @TaskAction
    public void generateGraphQLSourceTask() {
        init();
        GraphQLConfig graphQLConfig = BeanContext.get(GraphQLConfig.class);
        GraphQLConfigRegister configRegister = BeanContext.get(GraphQLConfigRegister.class);
        DocumentBuilder documentBuilder = BeanContext.get(DocumentBuilder.class);
        JavaFileBuilder javaFileBuilder = BeanContext.get(JavaFileBuilder.class);
        SourceSet sourceSet = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String javaPath = sourceSet.getJava().getSourceDirectories().filter(file -> file.getPath().contains(MAIN_JAVA_PATH)).getAsPath();
        try {
            configRegister.registerPackage(createClassLoader());
            if (graphQLConfig.getBuild()) {
                documentBuilder.build();
            }
            registerInvoke();
            documentBuilder.buildInvoker();
            javaFileBuilder.writeToPath(new File(javaPath));
        } catch (IOException | URISyntaxException e) {
            Logger.error(e);
            throw new TaskExecutionException(this, e);
        }
    }
}
