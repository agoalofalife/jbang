package dev.jbang.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import dev.jbang.Cache;
import dev.jbang.Settings;
import dev.jbang.dependencies.DependencyResolver;
import dev.jbang.dependencies.MavenRepo;
import dev.jbang.dependencies.ModularClassPath;
import dev.jbang.source.generators.JarCmdGenerator;
import dev.jbang.source.generators.JshCmdGenerator;

/**
 * This class gives access to all information necessary to turn source files
 * into something that can be executed. Typically, this means that it holds
 * references to source files, resources and dependencies which can be used by a
 * <code>Builder</code> to create a JAR file, for example.
 */
public class Project implements Code {
	@Nonnull
	private final ResourceRef resourceRef;
	private Source mainSource;

	// Public (user) input values (can be changed from the outside at any time)
	private final SourceSet mainSourceSet = new SourceSet();
	private final List<MavenRepo> repositories = new ArrayList<>();
	private final List<String> runtimeOptions = new ArrayList<>();
	private Map<String, String> properties = new HashMap<>();
	private final Map<String, String> manifestAttributes = new LinkedHashMap<>();
	private String javaVersion;
	private String description;
	private String gav;
	private String mainClass;
	private boolean nativeImage;

	// Cached values
	private Path jarFile;
	private Jar jar;
	private ModularClassPath mcp;

	public static final String ATTR_PREMAIN_CLASS = "Premain-Class";
	public static final String ATTR_AGENT_CLASS = "Agent-Class";

	public enum BuildFile {
		jbang("build.jbang");

		public final String fileName;

		BuildFile(String fileName) {
			this.fileName = fileName;
		}

		public static List<String> fileNames() {
			return Arrays.stream(values()).map(v -> v.fileName).collect(Collectors.toList());
		}
	}

	public Project(@Nonnull ResourceRef resourceRef) {
		this.resourceRef = resourceRef;
		if (Code.isJar(resourceRef.getFile())) {
			jarFile = resourceRef.getFile();
		}
	}

	// TODO This should be refactored and removed
	public Project(@Nonnull Source mainSource) {
		this.resourceRef = mainSource.getResourceRef();
		this.mainSource = mainSource;
	}

	@Override
	@Nonnull
	public ResourceRef getResourceRef() {
		return resourceRef;
	}

	@Nonnull
	public SourceSet getMainSourceSet() {
		return mainSourceSet;
	}

	@Nonnull
	public List<MavenRepo> getRepositories() {
		return Collections.unmodifiableList(repositories);
	}

	@Nonnull
	public Project addRepository(@Nonnull MavenRepo repository) {
		repositories.add(repository);
		return this;
	}

	@Nonnull
	public Project addRepositories(@Nonnull Collection<MavenRepo> repositories) {
		this.repositories.addAll(repositories);
		return this;
	}

	@Nonnull
	public List<String> getRuntimeOptions() {
		return Collections.unmodifiableList(runtimeOptions);
	}

	@Nonnull
	public Project addRuntimeOption(@Nonnull String option) {
		runtimeOptions.add(option);
		return this;
	}

	@Nonnull
	public Project addRuntimeOptions(@Nonnull Collection<String> options) {
		runtimeOptions.addAll(options);
		return this;
	}

	@Nonnull
	public Map<String, String> getProperties() {
		return properties;
	}

	public void setProperties(@Nonnull Map<String, String> properties) {
		this.properties = properties;
	}

	@Nonnull
	public Map<String, String> getManifestAttributes() {
		return manifestAttributes;
	}

	public void setAgentMainClass(String agentMainClass) {
		manifestAttributes.put(ATTR_AGENT_CLASS, agentMainClass);
	}

	public void setPreMainClass(String preMainClass) {
		manifestAttributes.put(ATTR_PREMAIN_CLASS, preMainClass);
	}

	@Nullable
	public String getJavaVersion() {
		return javaVersion;
	}

	@Nonnull
	public Project setJavaVersion(String javaVersion) {
		this.javaVersion = javaVersion;
		return this;
	}

	@Nonnull
	public Optional<String> getDescription() {
		return Optional.ofNullable(description);
	}

	@Nonnull
	public Project setDescription(String description) {
		this.description = description;
		return this;
	}

	@Nonnull
	public Optional<String> getGav() {
		return Optional.ofNullable(gav);
	}

	@Nonnull
	public Project setGav(String gav) {
		this.gav = gav;
		return this;
	}

	@Override
	public String getMainClass() {
		return mainClass;
	}

	public void setMainClass(String mainClass) {
		this.mainClass = mainClass;
	}

	public boolean isNativeImage() {
		return nativeImage;
	}

	public void setNativeImage(boolean isNative) {
		this.nativeImage = isNative;
	}

	@Override
	public boolean enableCDS() {
		return mainSource != null && mainSource.enableCDS();
	}

	@Nonnull
	public ModularClassPath resolveClassPath() {
		if (mcp == null) {
			DependencyResolver resolver = new DependencyResolver();
			updateDependencyResolver(resolver);
			mcp = resolver.resolve();
		}
		return mcp;
	}

	@Nonnull
	public DependencyResolver updateDependencyResolver(DependencyResolver resolver) {
		resolver.addRepositories(repositories);
		return getMainSourceSet().updateDependencyResolver(resolver);
	}

	@Nullable
	public Source getMainSource() {
		return mainSource;
	}

	public void setMainSource(Source mainSource) {
		this.mainSource = mainSource;
	}

	@Override
	public Path getJarFile() {
		if (isJShell()) {
			return null;
		}
		if (jarFile == null) {
			Path baseDir = Settings.getCacheDir(Cache.CacheClass.jars);
			Path tmpJarDir = baseDir.resolve(
					getResourceRef().getFile().getFileName() + "." + getMainSourceSet().getStableId());
			jarFile = tmpJarDir.getParent().resolve(tmpJarDir.getFileName() + ".jar");
		}
		return jarFile;
	}

	@Override
	public Jar asJar() {
		if (jar == null) {
			Path f = getJarFile();
			if (f != null && Files.exists(f)) {
				jar = Jar.prepareJar(this);
			}
		}
		return jar;
	}

	@Override
	public Project asProject() {
		return this;
	}

	/**
	 * Returns a <code>Builder</code> that can be used to turn this
	 * <code>Project</code> into executable code.
	 * 
	 * @return A <code>Builder</code>
	 */
	@Override
	@Nonnull
	public Builder builder() {
		if (mainSource != null) {
			return mainSource.getBuilder(this);
		} else {
			return this::asJar;
		}
	}

	/**
	 * Returns a <code>CmdGenerator</code> that can be used to generate the command
	 * line which, when used in a shell or any other CLI, would run this
	 * <code>Project</code>'s code.
	 * 
	 * @param ctx A reference to a <code>RunContext</code>
	 * @return A <code>CmdGenerator</code>
	 */
	@Override
	@Nonnull
	public CmdGenerator cmdGenerator(RunContext ctx) {
		if (isJShell() || ctx.getForceType() == Source.Type.jshell || ctx.isInteractive()) {
			return new JshCmdGenerator(this, ctx);
		} else {
			return new JarCmdGenerator(this, ctx);
		}
	}
}
