package io.graphoenix.gradle.task;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import io.graphoenix.core.config.PackageConfig;
import io.graphoenix.core.handler.DocumentManager;
import io.graphoenix.core.handler.GraphQLConfigRegister;
import io.graphoenix.spi.annotation.Application;
import io.graphoenix.spi.annotation.Package;
import io.graphoenix.spi.error.GraphQLErrors;
import io.graphoenix.spi.graphql.common.ArrayValueWithVariable;
import io.graphoenix.spi.graphql.common.Directive;
import io.graphoenix.spi.graphql.common.ObjectValueWithVariable;
import io.graphoenix.spi.graphql.type.*;
import io.nozdormu.config.TypesafeConfig;
import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.context.BeanContext;
import jakarta.annotation.Generated;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.graphql.Enum;
import org.eclipse.microprofile.graphql.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskExecutionException;
import org.tinylog.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.javaparser.resolution.types.ResolvedPrimitiveType.*;
import static io.graphoenix.core.utils.TypeNameUtil.getArgumentTypeName0;
import static io.graphoenix.core.utils.TypeNameUtil.getClassName;
import static io.graphoenix.spi.constant.Hammurabi.*;
import static io.graphoenix.spi.error.GraphQLErrorType.UNSUPPORTED_FIELD_TYPE;

public class BaseTask extends DefaultTask {

    private final DocumentManager documentManager = BeanContext.get(DocumentManager.class);
    private final Config config = BeanContext.get(Config.class);
    private final PackageConfig packageConfig = BeanContext.get(PackageConfig.class);
    private final GraphQLConfigRegister configRegister = BeanContext.get(GraphQLConfigRegister.class);

    protected static final String MAIN_PATH = "src" + File.separator + "main";
    protected static final String MAIN_JAVA_PATH = MAIN_PATH + File.separator + "java";
    protected static final String MAIN_RESOURCES_PATH = MAIN_PATH + File.separator + "resources";

    protected void init() {
        SourceSet sourceSet = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String resourcePath = sourceSet.getResources().getSourceDirectories().getAsPath();

        try {
            ClassLoader classLoader = createClassLoader();
            BeanContext.load(classLoader);
            ((TypesafeConfig) config).load(resourcePath);
            findDefaultPackageName().ifPresent(packageConfig::setPackageName);
            documentManager.getDocument().clear();
            configRegister.registerConfig(resourcePath);
        } catch (IOException e) {
            Logger.error(e);
            throw new TaskExecutionException(this, e);
        }
    }

    protected ClassLoader createClassLoader() throws TaskExecutionException {
        List<URL> urls = new ArrayList<>();
        SourceSetContainer sourceSets = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();
        try {
            for (SourceSet sourceSet : sourceSets) {
                for (File file : sourceSet.getCompileClasspath()) {
                    urls.add(file.toURI().toURL());
                }
                for (File classesDir : sourceSet.getOutput().getClassesDirs()) {
                    urls.add(classesDir.toURI().toURL());
                }
            }
        } catch (MalformedURLException e) {
            Logger.error(e);
            throw new TaskExecutionException(this, e);
        }
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    protected List<CompilationUnit> buildCompilationUnits() throws IOException {
        SourceSet sourceSet = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String javaPath = sourceSet.getJava().getSourceDirectories().filter(file -> file.getPath().contains(MAIN_JAVA_PATH)).getAsPath();
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        Path path = Path.of(javaPath);
        JavaParserTypeSolver javaParserTypeSolver = new JavaParserTypeSolver(path);
        ClassLoaderTypeSolver classLoaderTypeSolver = new ClassLoaderTypeSolver(createClassLoader());
        ReflectionTypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        combinedTypeSolver.add(javaParserTypeSolver);
        combinedTypeSolver.add(classLoaderTypeSolver);
        combinedTypeSolver.add(reflectionTypeSolver);
        JavaSymbolSolver javaSymbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        SourceRoot sourceRoot = new SourceRoot(path);
        sourceRoot.getParserConfiguration().setSymbolResolver(javaSymbolSolver);
        sourceRoot.tryToParse();
        return sourceRoot.getCompilationUnits();
    }

    public Optional<String> findDefaultPackageName() throws IOException {
        SourceSet sourceSet = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String javaPath = sourceSet.getJava().getSourceDirectories().filter(file -> file.getPath().contains(MAIN_JAVA_PATH)).getAsPath();
        Path path = Path.of(javaPath);
        JavaParserTypeSolver javaParserTypeSolver = new JavaParserTypeSolver(path);
        SourceRoot sourceRoot = new SourceRoot(path);
        JavaSymbolSolver javaSymbolSolver = new JavaSymbolSolver(javaParserTypeSolver);
        sourceRoot.getParserConfiguration().setSymbolResolver(javaSymbolSolver);
        sourceRoot.tryToParse();

        return sourceRoot.getCompilationUnits().stream()
                .flatMap(compilationUnit -> compilationUnit.getPackageDeclaration().stream())
                .filter(packageDeclaration -> packageDeclaration.getAnnotationByClass(Package.class).isPresent())
                .findFirst()
                .map(NodeWithName::getNameAsString)
                .or(() ->
                        sourceRoot.getCompilationUnits().stream()
                                .filter(compilationUnit ->
                                        compilationUnit.getTypes().stream()
                                                .anyMatch(typeDeclaration -> typeDeclaration.getAnnotationByClass(Application.class).isPresent())
                                )
                                .findFirst()
                                .flatMap(CompilationUnit::getPackageDeclaration)
                                .map(NodeWithName::getNameAsString)
                );
    }

    protected void registerInvoke() throws IOException {
        registerInvoke(buildCompilationUnits());
    }

    protected void registerInvoke(List<CompilationUnit> compilations) {
        List<ResolvedReferenceTypeDeclaration> objectTypeList = compilations.stream()
                .flatMap(compilationUnit -> compilationUnit.getTypes().stream())
                .map(TypeDeclaration::resolve)
                .filter(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.hasAnnotation(org.eclipse.microprofile.graphql.Type.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !resolvedReferenceTypeDeclaration.hasAnnotation(Generated.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !documentManager.getDocument().hasDefinition(findTypeName(resolvedReferenceTypeDeclaration)))
                .collect(Collectors.toList());

        List<ResolvedReferenceTypeDeclaration> interfaceTypeList = compilations.stream()
                .flatMap(compilationUnit -> compilationUnit.getTypes().stream())
                .map(TypeDeclaration::resolve)
                .filter(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.hasAnnotation(Interface.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !resolvedReferenceTypeDeclaration.hasAnnotation(Generated.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !documentManager.getDocument().hasDefinition(findTypeName(resolvedReferenceTypeDeclaration)))
                .collect(Collectors.toList());

        List<ResolvedReferenceTypeDeclaration> inputTypeList = compilations.stream()
                .flatMap(compilationUnit -> compilationUnit.getTypes().stream())
                .map(TypeDeclaration::resolve)
                .filter(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.hasAnnotation(Input.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !resolvedReferenceTypeDeclaration.hasAnnotation(Generated.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !documentManager.getDocument().hasDefinition(findTypeName(resolvedReferenceTypeDeclaration)))
                .collect(Collectors.toList());

        List<ResolvedReferenceTypeDeclaration> enumTypeList = compilations.stream()
                .flatMap(compilationUnit -> compilationUnit.getTypes().stream())
                .map(TypeDeclaration::resolve)
                .filter(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.hasAnnotation(Enum.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !resolvedReferenceTypeDeclaration.hasAnnotation(Generated.class.getCanonicalName()))
                .filter(resolvedReferenceTypeDeclaration -> !documentManager.getDocument().hasDefinition(findTypeName(resolvedReferenceTypeDeclaration)))
                .collect(Collectors.toList());

        objectTypeList.stream()
                .map(this::buildObject)
                .forEach(objectType -> documentManager.getDocument().addDefinition(objectType));

        interfaceTypeList.stream()
                .map(this::buildInterface)
                .forEach(interfaceType -> documentManager.getDocument().addDefinition(interfaceType));

        inputTypeList.stream()
                .map(this::buildInputObject)
                .forEach(interfaceType -> documentManager.getDocument().addDefinition(interfaceType));

        enumTypeList.stream()
                .map(this::buildEnum)
                .forEach(enumType -> documentManager.getDocument().addDefinition(enumType));

        compilations.forEach(compilationUnit ->
                compilationUnit.getTypes().stream()
                        .filter(typeDeclaration -> typeDeclaration.isAnnotationPresent(GraphQLApi.class))
                        .forEach(typeDeclaration ->
                                typeDeclaration.getMethods().stream()
                                        .filter(methodDeclaration -> !methodDeclaration.isAnnotationPresent(Query.class))
                                        .filter(methodDeclaration -> !methodDeclaration.isAnnotationPresent(Mutation.class))
                                        .filter(methodDeclaration ->
                                                methodDeclaration.getParameters().stream()
                                                        .anyMatch(parameter ->
                                                                parameter.isAnnotationPresent(Source.class) &&
                                                                        parameter.getType().isClassOrInterfaceType() &&
                                                                        documentManager.getDocument().getDefinition(getTypeName(parameter.getType())).isObject()
                                                        )
                                        )
                                        .forEach(methodDeclaration -> {
                                                    String objectName = getTypeName(
                                                            methodDeclaration.getParameters().stream()
                                                                    .filter(parameter -> parameter.isAnnotationPresent(Source.class))
                                                                    .filter(parameter -> parameter.getType().isClassOrInterfaceType())
                                                                    .filter(parameter -> documentManager.getDocument().getDefinition(getTypeName(parameter.getType())).isObject())
                                                                    .findFirst()
                                                                    .orElseThrow(() -> new RuntimeException("@Source annotation parameter not exist in " + methodDeclaration.getNameAsString()))
                                                                    .getType()
                                                    );

                                                    documentManager.getDocument().getObjectTypeOrError(objectName)
                                                            .addField(new FieldDefinition(getSourceNameFromMethodDeclaration(methodDeclaration).orElseGet(() -> getInvokeFieldName(methodDeclaration.getName().getIdentifier())))
                                                                    .setType(getInvokeFieldTypeName(methodDeclaration.getType()))
                                                                    .addDirective(
                                                                            new Directive(DIRECTIVE_INVOKE_NAME)
                                                                                    .addArgument(DIRECTIVE_INVOKE_ARGUMENT_CLASS_NAME_NAME,
                                                                                            typeDeclaration.getFullyQualifiedName()
                                                                                                    .orElseGet(() ->
                                                                                                            compilationUnit.getPackageDeclaration()
                                                                                                                    .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                                                                                                                    .orElse("") +
                                                                                                                    typeDeclaration.getNameAsString()
                                                                                                    )
                                                                                    )
                                                                                    .addArgument(DIRECTIVE_INVOKE_ARGUMENT_METHOD_NAME_NAME, methodDeclaration.getNameAsString())
                                                                                    .addArgument(
                                                                                            DIRECTIVE_INVOKE_ARGUMENT_PARAMETER_NAME,
                                                                                            new ArrayValueWithVariable(
                                                                                                    methodDeclaration.getParameters().stream()
                                                                                                            .map(parameter ->
                                                                                                                    ObjectValueWithVariable
                                                                                                                            .of(
                                                                                                                                    INPUT_INVOKE_PARAMETER_INPUT_VALUE_NAME_NAME,
                                                                                                                                    parameter.getNameAsString(),
                                                                                                                                    INPUT_INVOKE_PARAMETER_INPUT_VALUE_CLASS_NAME_NAME,
                                                                                                                                    parameter.getType().toString()
                                                                                                                            )
                                                                                                            )
                                                                                                            .collect(Collectors.toList())
                                                                                            )
                                                                                    )
                                                                                    .addArgument("returnClassName", methodDeclaration.getType().toString())
                                                                    )
                                                                    .addDirective(
                                                                            new Directive(DIRECTIVE_PACKAGE_NAME)
                                                                                    .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                                                                    )
                                                            );
                                                }
                                        )
                        )
        );

        compilations.forEach(compilationUnit ->
                compilationUnit.getTypes().stream()
                        .filter(typeDeclaration -> typeDeclaration.isAnnotationPresent(GraphQLApi.class))
                        .forEach(typeDeclaration ->
                                typeDeclaration.getMethods().stream()
                                        .filter(methodDeclaration -> !methodDeclaration.isAnnotationPresent(Query.class))
                                        .filter(methodDeclaration -> !methodDeclaration.isAnnotationPresent(Mutation.class))
                                        .filter(methodDeclaration ->
                                                methodDeclaration.getParameters().stream()
                                                        .anyMatch(parameter ->
                                                                parameter.isAnnotationPresent(Source.class) &&
                                                                        parameter.getType().isClassOrInterfaceType() &&
                                                                        documentManager.getDocument().getDefinition(getTypeName(parameter.getType())).isInterface()
                                                        )
                                        )
                                        .forEach(methodDeclaration -> {
                                                    String interfaceName = getTypeName(
                                                            methodDeclaration.getParameters().stream()
                                                                    .filter(parameter -> parameter.isAnnotationPresent(Source.class))
                                                                    .filter(parameter -> parameter.getType().isClassOrInterfaceType())
                                                                    .filter(parameter -> documentManager.getDocument().getDefinition(getTypeName(parameter.getType())).isInterface())
                                                                    .findFirst()
                                                                    .orElseThrow(() -> new RuntimeException("@Source annotation parameter not exist in " + methodDeclaration.getNameAsString()))
                                                                    .getType()
                                                    );

                                                    documentManager.getDocument().getImplementsObjectType(interfaceName)
                                                            .forEach(objectType ->
                                                                    objectType.addField(
                                                                            new FieldDefinition(getSourceNameFromMethodDeclaration(methodDeclaration).orElseGet(() -> getInvokeFieldName(methodDeclaration.getName().getIdentifier())))
                                                                                    .setType(getInvokeFieldTypeName(methodDeclaration.getType()))
                                                                                    .addDirective(
                                                                                            new Directive(DIRECTIVE_INVOKE_NAME)
                                                                                                    .addArgument(DIRECTIVE_INVOKE_ARGUMENT_CLASS_NAME_NAME,
                                                                                                            typeDeclaration.getFullyQualifiedName()
                                                                                                                    .orElseGet(() ->
                                                                                                                            compilationUnit.getPackageDeclaration()
                                                                                                                                    .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                                                                                                                                    .orElse("") +
                                                                                                                                    typeDeclaration.getNameAsString()
                                                                                                                    )
                                                                                                    )
                                                                                                    .addArgument(DIRECTIVE_INVOKE_ARGUMENT_METHOD_NAME_NAME, methodDeclaration.isAnnotationPresent(Async.class) ? getAsyncMethodName(methodDeclaration) : methodDeclaration.getNameAsString())
                                                                                                    .addArgument(
                                                                                                            DIRECTIVE_INVOKE_ARGUMENT_PARAMETER_NAME,
                                                                                                            new ArrayValueWithVariable(
                                                                                                                    methodDeclaration.getParameters().stream()
                                                                                                                            .map(parameter ->
                                                                                                                                    ObjectValueWithVariable.of(
                                                                                                                                            INPUT_INVOKE_PARAMETER_INPUT_VALUE_NAME_NAME,
                                                                                                                                            parameter.getNameAsString(),
                                                                                                                                            INPUT_INVOKE_PARAMETER_INPUT_VALUE_CLASS_NAME_NAME,
                                                                                                                                            parameter.getType().toString()
                                                                                                                                    )
                                                                                                                            )
                                                                                                                            .collect(Collectors.toList())
                                                                                                            )
                                                                                                    )
                                                                                                    .addArgument(DIRECTIVE_INVOKE_ARGUMENT_RETURN_CLASS_NAME_NAME, methodDeclaration.getType().toString())
                                                                                                    .addArgument(DIRECTIVE_INVOKE_ASYNC_NAME, methodDeclaration.isAnnotationPresent(Async.class))
                                                                                    )
                                                                                    .addDirective(
                                                                                            new Directive(DIRECTIVE_PACKAGE_NAME)
                                                                                                    .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                                                                                    )
                                                                    )
                                                            );
                                                }
                                        )
                        )
        );

        compilations.forEach(compilationUnit ->
                compilationUnit.getTypes().stream()
                        .filter(typeDeclaration -> typeDeclaration.isAnnotationPresent(GraphQLApi.class))
                        .forEach(typeDeclaration ->
                                typeDeclaration.getMethods().stream()
                                        .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Query.class))
                                        .forEach(methodDeclaration ->
                                                documentManager.getDocument().getQueryOperationType()
                                                        .orElseGet(() ->
                                                                (ObjectType) documentManager.getDocument()
                                                                        .addDefinition(new ObjectType(TYPE_QUERY_NAME))
                                                                        .getDefinition(TYPE_QUERY_NAME)
                                                        )
                                                        .addField(
                                                                new FieldDefinition(getQueryNameFromMethodDeclaration(methodDeclaration).orElseGet(() -> getInvokeFieldName(methodDeclaration.getName().getIdentifier())))
                                                                        .setType(getInvokeFieldTypeName(methodDeclaration.getType()))
                                                                        .setArguments(
                                                                                methodDeclaration.getParameters().stream()
                                                                                        .map(parameter ->
                                                                                                new InputValue(parameter.getName().getIdentifier())
                                                                                                        .setType(getInvokeFieldArgumentTypeName(getInvokeFieldTypeName(parameter.getType()))))
                                                                                        .collect(Collectors.toCollection(LinkedHashSet::new))
                                                                        )
                                                                        .addDirective(
                                                                                new Directive(DIRECTIVE_INVOKE_NAME)
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_CLASS_NAME_NAME,
                                                                                                typeDeclaration.getFullyQualifiedName()
                                                                                                        .orElseGet(() ->
                                                                                                                compilationUnit.getPackageDeclaration()
                                                                                                                        .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                                                                                                                        .orElse("") +
                                                                                                                        typeDeclaration.getNameAsString()
                                                                                                        )
                                                                                        )
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_METHOD_NAME_NAME, methodDeclaration.isAnnotationPresent(Async.class) ? getAsyncMethodName(methodDeclaration) : methodDeclaration.getNameAsString())
                                                                                        .addArgument(
                                                                                                DIRECTIVE_INVOKE_ARGUMENT_PARAMETER_NAME,
                                                                                                new ArrayValueWithVariable(
                                                                                                        methodDeclaration.getParameters().stream()
                                                                                                                .map(parameter ->
                                                                                                                        ObjectValueWithVariable.of(
                                                                                                                                INPUT_INVOKE_PARAMETER_INPUT_VALUE_NAME_NAME,
                                                                                                                                parameter.getNameAsString(),
                                                                                                                                INPUT_INVOKE_PARAMETER_INPUT_VALUE_CLASS_NAME_NAME,
                                                                                                                                parameter.getType().toString()
                                                                                                                        )
                                                                                                                )
                                                                                                                .collect(Collectors.toList())
                                                                                                )
                                                                                        )
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_RETURN_CLASS_NAME_NAME, methodDeclaration.getType().toString())
                                                                                        .addArgument(DIRECTIVE_INVOKE_ASYNC_NAME, methodDeclaration.isAnnotationPresent(Async.class))
                                                                        )
                                                                        .addDirective(
                                                                                new Directive(DIRECTIVE_PACKAGE_NAME)
                                                                                        .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                                                                        )
                                                        )
                                        )
                        )
        );

        compilations.forEach(compilationUnit ->
                compilationUnit.getTypes().stream()
                        .filter(typeDeclaration -> typeDeclaration.isAnnotationPresent(GraphQLApi.class))
                        .forEach(typeDeclaration ->
                                typeDeclaration.getMethods().stream()
                                        .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Mutation.class))
                                        .forEach(methodDeclaration ->
                                                documentManager.getDocument().getMutationOperationType()
                                                        .orElseGet(() ->
                                                                (ObjectType) documentManager.getDocument()
                                                                        .addDefinition(new ObjectType(TYPE_MUTATION_NAME))
                                                                        .getDefinition(TYPE_MUTATION_NAME)
                                                        )
                                                        .addField(
                                                                new FieldDefinition(getMutationNameFromMethodDeclaration(methodDeclaration).orElseGet(() -> getInvokeFieldName(methodDeclaration.getName().getIdentifier())))
                                                                        .setType(getInvokeFieldTypeName(methodDeclaration.getType()))
                                                                        .setArguments(
                                                                                methodDeclaration.getParameters().stream()
                                                                                        .map(parameter ->
                                                                                                new InputValue(parameter.getName().getIdentifier())
                                                                                                        .setType(getInvokeFieldArgumentTypeName(getInvokeFieldTypeName(parameter.getType()))))
                                                                                        .collect(Collectors.toCollection(LinkedHashSet::new))
                                                                        )
                                                                        .addDirective(
                                                                                new Directive(DIRECTIVE_INVOKE_NAME)
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_CLASS_NAME_NAME,
                                                                                                typeDeclaration.getFullyQualifiedName()
                                                                                                        .orElseGet(() ->
                                                                                                                compilationUnit.getPackageDeclaration()
                                                                                                                        .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                                                                                                                        .orElse("") +
                                                                                                                        typeDeclaration.getNameAsString()
                                                                                                        )
                                                                                        )
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_METHOD_NAME_NAME, methodDeclaration.isAnnotationPresent(Async.class) ? getAsyncMethodName(methodDeclaration) : methodDeclaration.getNameAsString())
                                                                                        .addArgument(
                                                                                                DIRECTIVE_INVOKE_ARGUMENT_PARAMETER_NAME,
                                                                                                new ArrayValueWithVariable(
                                                                                                        methodDeclaration.getParameters().stream()
                                                                                                                .map(parameter ->
                                                                                                                        Map.of(
                                                                                                                                INPUT_INVOKE_PARAMETER_INPUT_VALUE_NAME_NAME,
                                                                                                                                parameter.getNameAsString(),
                                                                                                                                INPUT_INVOKE_PARAMETER_INPUT_VALUE_CLASS_NAME_NAME,
                                                                                                                                parameter.getType().toString()
                                                                                                                        )
                                                                                                                )
                                                                                                                .collect(Collectors.toList())
                                                                                                )
                                                                                        )
                                                                                        .addArgument(DIRECTIVE_INVOKE_ARGUMENT_RETURN_CLASS_NAME_NAME, methodDeclaration.getType().toString())
                                                                                        .addArgument(DIRECTIVE_INVOKE_ASYNC_NAME, methodDeclaration.isAnnotationPresent(Async.class))
                                                                        )
                                                                        .addDirective(
                                                                                new Directive(DIRECTIVE_PACKAGE_NAME)
                                                                                        .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                                                                        )
                                                        )
                                        )
                        )
        );
    }

    protected Optional<ResolvedReferenceTypeDeclaration> resolve(Type type) {
        if (type.isArrayType()) {
            return resolve(type.asArrayType().getElementType());
        } else if (type.isReferenceType()) {
            try {
                ResolvedReferenceType resolvedReferenceType = type.resolve().asReferenceType();
                return resolve(resolvedReferenceType);
            } catch (UnsolvedSymbolException e) {
                Logger.warn(e);
            }
        }
        return Optional.empty();
    }

    protected String getTypeName(Type type) {
        try {
            if (type.isArrayType()) {
                return getInvokeFieldTypeName(getTypeName(type.asArrayType().getElementType()) + "[]").getTypeName().getName();
            } else if (type.isPrimitiveType()) {
                return getInvokeFieldTypeName(type.asPrimitiveType()).getTypeName().getName();
            } else if (type.isClassOrInterfaceType()) {
                return getInvokeFieldTypeName(type.asClassOrInterfaceType().resolve().asReferenceType()).getTypeName().getName();
            }
        } catch (UnsolvedSymbolException e) {
            Logger.warn(e);
        }
        return type.asString();
    }

    protected Optional<ResolvedReferenceTypeDeclaration> resolve(ResolvedReferenceType resolvedReferenceType) {
        String qualifiedName = resolvedReferenceType.getQualifiedName();
        if (qualifiedName.equals(Mono.class.getCanonicalName()) ||
                qualifiedName.equals(Flux.class.getCanonicalName()) ||
                qualifiedName.equals(Collection.class.getCanonicalName()) ||
                qualifiedName.equals(List.class.getCanonicalName()) ||
                qualifiedName.equals(Set.class.getCanonicalName())) {
            return resolve(resolvedReferenceType.typeParametersValues().get(0).asReferenceType());
        } else {
            return resolvedReferenceType.getTypeDeclaration();
        }
    }

    protected ObjectType buildObject(ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration) {
        return new ObjectType(findTypeName(resolvedReferenceTypeDeclaration))
                .setFields(
                        resolvedReferenceTypeDeclaration.getDeclaredMethods().stream()
                                .filter(resolvedMethodDeclaration -> !resolvedMethodDeclaration.getReturnType().isVoid())
                                .filter(resolvedMethodDeclaration -> resolvedMethodDeclaration.getName().startsWith("get"))
                                .map(this::buildField)
                                .collect(Collectors.toSet())
                )
                .addDirective(
                        new Directive(DIRECTIVE_CLASS_NAME)
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_NAME_NAME, resolvedReferenceTypeDeclaration.getQualifiedName())
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_EXISTS_NAME, true)
                )
                .addDirective(
                        new Directive(DIRECTIVE_PACKAGE_NAME)
                                .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                )
                .addDirective(new Directive(DIRECTIVE_CONTAINER_NAME));
    }

    protected InterfaceType buildInterface(ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration) {
        return new InterfaceType(findTypeName(resolvedReferenceTypeDeclaration))
                .setFields(
                        resolvedReferenceTypeDeclaration.getDeclaredMethods().stream()
                                .filter(resolvedMethodDeclaration -> !resolvedMethodDeclaration.getReturnType().isVoid())
                                .filter(resolvedMethodDeclaration -> resolvedMethodDeclaration.getName().startsWith("get"))
                                .map(this::buildField)
                                .collect(Collectors.toSet())
                )
                .addDirective(
                        new Directive(DIRECTIVE_CLASS_NAME)
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_NAME_NAME, resolvedReferenceTypeDeclaration.getQualifiedName())
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_EXISTS_NAME, true)
                )
                .addDirective(
                        new Directive(DIRECTIVE_PACKAGE_NAME)
                                .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                )
                .addDirective(new Directive(DIRECTIVE_CONTAINER_NAME));
    }

    protected InputObjectType buildInputObject(ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration) {
        return new InputObjectType(findTypeName(resolvedReferenceTypeDeclaration))
                .setInputValues(
                        resolvedReferenceTypeDeclaration.getDeclaredMethods().stream()
                                .filter(resolvedMethodDeclaration -> !resolvedMethodDeclaration.getReturnType().isVoid())
                                .filter(resolvedMethodDeclaration -> resolvedMethodDeclaration.getName().startsWith("get"))
                                .map(this::buildInputValue)
                                .collect(Collectors.toSet())
                )
                .addDirective(
                        new Directive(DIRECTIVE_CLASS_NAME)
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_NAME_NAME, resolvedReferenceTypeDeclaration.getQualifiedName())
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_EXISTS_NAME, true)
                )
                .addDirective(
                        new Directive(DIRECTIVE_PACKAGE_NAME)
                                .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                );
    }

    protected EnumType buildEnum(ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration) {
        return new EnumType(findTypeName(resolvedReferenceTypeDeclaration))
                .setEnumValues(
                        resolvedReferenceTypeDeclaration.asEnum().getEnumConstants().stream()
                                .map(this::buildEnumValue)
                                .collect(Collectors.toSet())
                )
                .addDirective(
                        new Directive(DIRECTIVE_CLASS_NAME)
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_NAME_NAME, resolvedReferenceTypeDeclaration.getQualifiedName())
                                .addArgument(DIRECTIVE_CLASS_ARGUMENT_EXISTS_NAME, true)
                )
                .addDirective(
                        new Directive(DIRECTIVE_PACKAGE_NAME)
                                .addArgument(DIRECTIVE_PACKAGE_ARGUMENT_NAME_NAME, packageConfig.getPackageName())
                );
    }

    protected String findTypeName(ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration) {
        if (resolvedReferenceTypeDeclaration instanceof JavaParserClassDeclaration) {
            return ((JavaParserClassDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(Name.class)
                    .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                    .filter(name -> !Strings.isNullOrEmpty(name))
                    .orElseGet(() -> ((JavaParserClassDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(org.eclipse.microprofile.graphql.Type.class)
                            .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                            .filter(name -> !Strings.isNullOrEmpty(name))
                            .orElse(resolvedReferenceTypeDeclaration.getName()));
        } else if (resolvedReferenceTypeDeclaration instanceof JavaParserInterfaceDeclaration) {
            return ((JavaParserInterfaceDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(Name.class)
                    .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                    .filter(name -> !Strings.isNullOrEmpty(name))
                    .orElseGet(() -> ((JavaParserInterfaceDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(Interface.class)
                            .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                            .filter(name -> !Strings.isNullOrEmpty(name))
                            .orElse(resolvedReferenceTypeDeclaration.getName()));
        } else if (resolvedReferenceTypeDeclaration instanceof JavaParserEnumDeclaration) {
            return ((JavaParserEnumDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(Name.class)
                    .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                    .filter(name -> !Strings.isNullOrEmpty(name))
                    .orElseGet(() -> ((JavaParserEnumDeclaration) resolvedReferenceTypeDeclaration).getWrappedNode().getAnnotationByClass(Enum.class)
                            .flatMap(annotationExpr -> findAnnotationValue(annotationExpr).map(expression -> expression.asStringLiteralExpr().getValue()))
                            .filter(name -> !Strings.isNullOrEmpty(name))
                            .orElse(resolvedReferenceTypeDeclaration.getName()));
        }
        return resolvedReferenceTypeDeclaration.getName();
    }

    protected Optional<Expression> findAnnotationValue(AnnotationExpr annotationExpr) {
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return Optional.of(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                    .filter(memberValuePair -> memberValuePair.getNameAsString().equals("value"))
                    .findFirst()
                    .map(MemberValuePair::getValue);
        }
        return Optional.empty();
    }

    protected FieldDefinition buildField(ResolvedMethodDeclaration resolvedMethodDeclaration) {
        return new FieldDefinition(getInvokeFieldName(resolvedMethodDeclaration.getName()))
                .setType(getInvokeFieldTypeName(resolvedMethodDeclaration.getReturnType()));
    }

    protected InputValue buildInputValue(ResolvedMethodDeclaration resolvedMethodDeclaration) {
        return new InputValue(getInvokeFieldName(resolvedMethodDeclaration.getName()))
                .setType(getInvokeFieldTypeName(resolvedMethodDeclaration.getReturnType()));
    }

    protected EnumValueDefinition buildEnumValue(ResolvedEnumConstantDeclaration resolvedEnumConstantDeclaration) {
        return new EnumValueDefinition(getInvokeFieldName(resolvedEnumConstantDeclaration.getName()));
    }

    private Optional<String> getSourceNameFromMethodDeclaration(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getParameters().stream()
                .filter(parameter -> parameter.isAnnotationPresent(Source.class))
                .flatMap(parameter -> parameter.getAnnotationByClass(Source.class).stream())
                .findFirst()
                .flatMap(annotationExpr -> {
                            if (annotationExpr.isNormalAnnotationExpr()) {
                                return annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                        .filter(memberValuePair -> memberValuePair.getNameAsString().equals("name"))
                                        .filter(memberValuePair -> Strings.isNullOrEmpty(memberValuePair.getValue().asStringLiteralExpr().getValue()))
                                        .findFirst();
                            }
                            return Optional.empty();
                        }
                )
                .map(memberValuePair -> memberValuePair.getValue().asStringLiteralExpr().getValue());
    }

    private Optional<String> getQueryNameFromMethodDeclaration(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getAnnotationByClass(Query.class).stream()
                .findFirst()
                .flatMap(this::findAnnotationValue)
                .map(expression -> expression.asStringLiteralExpr().getValue());
    }

    private Optional<String> getMutationNameFromMethodDeclaration(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getAnnotationByClass(Mutation.class).stream()
                .findFirst()
                .flatMap(this::findAnnotationValue)
                .map(expression -> expression.asStringLiteralExpr().getValue());
    }

    private String getInvokeFieldName(String methodName) {
        if (methodName.startsWith("get")) {
            return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, methodName.replaceFirst("get", ""));
        } else if (methodName.startsWith("set")) {
            return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, methodName.replaceFirst("set", ""));
        } else {
            return methodName;
        }
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldTypeName(Type type) {
        if (type.isArrayType()) {
            return new ListType(getInvokeFieldTypeName(type.asArrayType().getComponentType()));
        } else if (type.isPrimitiveType()) {
            if (type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.SHORT) ||
                    type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.INT) ||
                    type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.LONG)) {
                return new TypeName(SCALA_INT_NAME);
            } else if (type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.FLOAT) ||
                    type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.DOUBLE)) {
                return new TypeName(SCALA_FLOAT_NAME);
            } else if (type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.CHAR) ||
                    type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.BYTE)) {
                return new TypeName(SCALA_STRING_NAME);
            } else if (type.asPrimitiveType().getType().equals(PrimitiveType.Primitive.BOOLEAN)) {
                return new TypeName(SCALA_BOOLEAN_NAME);
            }
        } else if (type.isReferenceType()) {
            try {
                ResolvedReferenceType resolvedReferenceType = type.resolve().asReferenceType();
                return getInvokeFieldTypeName(resolvedReferenceType);
            } catch (UnsolvedSymbolException e) {
                return getInvokeFieldTypeName(type.toString());
            }
        }
        throw new GraphQLErrors(UNSUPPORTED_FIELD_TYPE.bind(type.toString()));
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldTypeName(String typeName) {
        String className = getClassName(typeName);
        if (typeName.endsWith("[]")) {
            return new ListType(getInvokeFieldTypeName(typeName.replace("[]", "")));
        } else if (className.equals(Mono.class.getSimpleName())) {
            return getInvokeFieldTypeName(getArgumentTypeName0(typeName));
        } else if (className.equals(Collection.class.getSimpleName()) ||
                className.equals(List.class.getSimpleName()) ||
                className.equals(Set.class.getSimpleName()) ||
                className.equals(Flux.class.getSimpleName())) {
            return new ListType(getInvokeFieldTypeName(getArgumentTypeName0(typeName)));
        } else if (className.equals(int.class.getSimpleName()) ||
                className.equals(short.class.getSimpleName()) ||
                className.equals(byte.class.getSimpleName()) ||
                className.equals(Integer.class.getSimpleName()) ||
                className.equals(Short.class.getSimpleName()) ||
                className.equals(Byte.class.getSimpleName())) {
            return new TypeName(SCALA_INT_NAME);
        } else if (className.equals(float.class.getSimpleName()) ||
                className.equals(double.class.getSimpleName()) ||
                className.equals(Float.class.getSimpleName()) ||
                className.equals(Double.class.getSimpleName())) {
            return new TypeName(SCALA_FLOAT_NAME);
        } else if (className.equals(String.class.getSimpleName()) ||
                className.equals(char.class.getSimpleName()) ||
                className.equals(Character.class.getSimpleName())) {
            return new TypeName(SCALA_STRING_NAME);
        } else if (className.equals(boolean.class.getSimpleName()) ||
                className.equals(Boolean.class.getSimpleName())) {
            return new TypeName(SCALA_BOOLEAN_NAME);
        } else if (className.equals(BigInteger.class.getSimpleName())) {
            return new TypeName(SCALA_BIG_INTEGER_NAME);
        } else if (className.equals(BigDecimal.class.getSimpleName())) {
            return new TypeName(SCALA_BIG_DECIMAL_NAME);
        } else if (className.equals(LocalDate.class.getSimpleName())) {
            return new TypeName(SCALA_DATE_NAME);
        } else if (className.equals(LocalTime.class.getSimpleName())) {
            return new TypeName(SCALA_TIME_NAME);
        } else if (className.equals(LocalDateTime.class.getSimpleName())) {
            return new TypeName(SCALA_DATE_TIME_NAME);
        } else {
            return new TypeName(className.substring(className.lastIndexOf(".") + 1));
        }
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldArgumentTypeName(io.graphoenix.spi.graphql.type.Type type) {
        if (type.isList()) {
            return new ListType(getInvokeFieldArgumentTypeName(type.asListType().getType()));
        } else if (type.isNonNull()) {
            return new ListType(getInvokeFieldArgumentTypeName(type.asNonNullType().getType()));
        } else if (documentManager.getDocument().getDefinition(type.asTypeName().getName()).isLeaf() ||
                documentManager.getDocument().getDefinition(type.asTypeName().getName()).isInputObject()) {
            return new TypeName(type.asTypeName().getName());
        } else {
            return new TypeName(type.asTypeName().getName() + SUFFIX_INPUT);
        }
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldTypeName(ResolvedType resolvedType) {
        if (resolvedType.isArray()) {
            return new ListType(getInvokeFieldTypeName(resolvedType.asArrayType().getComponentType()));
        } else if (resolvedType.isPrimitive()) {
            return getInvokeFieldTypeName(resolvedType.asPrimitive());
        } else if (resolvedType.isReferenceType()) {
            return getInvokeFieldTypeName(resolvedType.asReferenceType());
        }
        throw new GraphQLErrors(UNSUPPORTED_FIELD_TYPE.bind(resolvedType.toString()));
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldTypeName(ResolvedPrimitiveType resolvedPrimitiveType) {
        if (resolvedPrimitiveType.in(new ResolvedPrimitiveType[]{SHORT, INT, LONG})) {
            return new TypeName(SCALA_INT_NAME);
        } else if (resolvedPrimitiveType.in(new ResolvedPrimitiveType[]{FLOAT, DOUBLE})) {
            return new TypeName(SCALA_FLOAT_NAME);
        } else if (resolvedPrimitiveType.in(new ResolvedPrimitiveType[]{BYTE, CHAR})) {
            return new TypeName(SCALA_STRING_NAME);
        } else if (resolvedPrimitiveType.isBoolean()) {
            return new TypeName(SCALA_BOOLEAN_NAME);
        }
        throw new GraphQLErrors(UNSUPPORTED_FIELD_TYPE.bind(resolvedPrimitiveType.toString()));
    }

    private io.graphoenix.spi.graphql.type.Type getInvokeFieldTypeName(ResolvedReferenceType resolvedReferenceType) {
        if (resolvedReferenceType.getQualifiedName().equals(Mono.class.getCanonicalName())) {
            return getInvokeFieldTypeName(resolvedReferenceType.typeParametersValues().get(0).asReferenceType());
        } else if (resolvedReferenceType.getQualifiedName().equals(Collection.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(List.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Set.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Flux.class.getCanonicalName())) {
            return new ListType(getInvokeFieldTypeName(resolvedReferenceType.typeParametersValues().get(0).asReferenceType()));
        } else if (resolvedReferenceType.getQualifiedName().equals(Integer.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Short.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Byte.class.getCanonicalName())) {
            return new TypeName(SCALA_INT_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(Float.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Double.class.getCanonicalName())) {
            return new TypeName(SCALA_FLOAT_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(String.class.getCanonicalName()) ||
                resolvedReferenceType.getQualifiedName().equals(Character.class.getCanonicalName())) {
            return new TypeName(SCALA_STRING_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(Boolean.class.getCanonicalName())) {
            return new TypeName(SCALA_BOOLEAN_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(BigInteger.class.getCanonicalName())) {
            return new TypeName(SCALA_BIG_INTEGER_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(BigDecimal.class.getCanonicalName())) {
            return new TypeName(SCALA_BIG_DECIMAL_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(LocalDate.class.getCanonicalName())) {
            return new TypeName(SCALA_DATE_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(LocalTime.class.getCanonicalName())) {
            return new TypeName(SCALA_TIME_NAME);
        } else if (resolvedReferenceType.getQualifiedName().equals(LocalDateTime.class.getCanonicalName())) {
            return new TypeName(SCALA_DATE_TIME_NAME);
        } else {
            return new TypeName(
                    resolvedReferenceType.getTypeDeclaration()
                            .map(this::findTypeName)
                            .orElseGet(() -> resolvedReferenceType.getQualifiedName().substring(resolvedReferenceType.getQualifiedName().lastIndexOf(".") + 1))
            );
        }
    }

    private String getAsyncMethodName(MethodDeclaration methodDeclaration) {
        return Stream
                .concat(
                        Stream.of(methodDeclaration.getNameAsString() + "Async"),
                        methodDeclaration.getParameters().stream()
                                .map(NodeWithType::getTypeAsString)
                )
                .collect(Collectors.joining("_"));
    }
}
