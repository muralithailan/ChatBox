package chatbox.command.javadoc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.SAXException;

import chatbox.util.Leaf;


public class JavadocZipFile {
	private static final String extension = ".xml";
	private static final String infoFileName = "info" + extension;

	/**
	 * Matches placeholders in the javadocUrlPattern field.
	 */
	private final Pattern javadocUrlPatternFieldRegex = Pattern.compile("\\{(.*?)(\\s+(.*?))?\\}");

	/**
	 * The file system path to the ZIP file.
	 */
	private final Path file;

	/**
	 * The base URL for the project's online Javadoc page (e.g.
	 * "http://www.example.com/javadocs/")
	 */
	private final String baseUrl;

	/**
	 * The project's name (e.g. "ez-vcard").
	 */
	private final String name;

	/**
	 * The version of the project that this Javadoc information is from.
	 */
	private final String version;

	/**
	 * The URL to the project's webpage.
	 */
	private final String projectUrl;

	/**
	 * Defines how the URL for a particular class's Javadoc page should be
	 * constructed.
	 */
	private final String javadocUrlPattern;

	/**
	 * @param file the ZIP file
	 * @throws IOException if there's a problem reading the metadata from the
	 * file
	 */
	public JavadocZipFile(Path file) throws IOException {
		this.file = file.toRealPath();

		Leaf document;
		try (FileSystem fs = open()) {
			Path info = fs.getPath("/" + infoFileName);
			if (!Files.exists(info)) {
				baseUrl = name = version = projectUrl = javadocUrlPattern = null;
				return;
			}

			document = parseXml(info);
		}

		Leaf infoElement = document.selectFirst("/info");
		if (infoElement == null) {
			baseUrl = name = version = projectUrl = javadocUrlPattern = null;
			return;
		}

		String name = infoElement.attribute("name");
		this.name = name.isEmpty() ? null : name;

		String baseUrl = infoElement.attribute("baseUrl");
		if (baseUrl.isEmpty()) {
			this.baseUrl = null;
		} else {
			//make sure the base URL ends with a "/"
			this.baseUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/");
		}

		String projectUrl = infoElement.attribute("projectUrl");
		this.projectUrl = projectUrl.isEmpty() ? null : projectUrl;

		String version = infoElement.attribute("version");
		this.version = version.isEmpty() ? null : version;

		String javadocUrlPattern = infoElement.attribute("javadocUrlPattern");
		this.javadocUrlPattern = javadocUrlPattern.isEmpty() ? null : javadocUrlPattern;
	}

	/**
	 * Gets the URL to a class's Javadoc page.
	 * @param info the class
	 * @param frames true to get the URL to the version of the web page with
	 * frames, false not to
	 * @return the URL or null if no base URL was defined in this ZIP file
	 */
	public String getUrl(ClassInfo info, boolean frames) {
		if (javadocUrlPattern != null) {
			return javadocUrlPattern(info);
		}

		if (baseUrl == null) {
			return null;
		}

		StringBuilder sb = new StringBuilder(baseUrl);
		if (frames) {
			sb.append("index.html?");
		}
		ClassName className = info.getName();
		if (className.getPackageName() != null) {
			sb.append(className.getPackageName().replace('.', '/')).append('/');
		}
		for (String outerClass : className.getOuterClassNames()) {
			sb.append(outerClass).append('.');
		}
		sb.append(className.getSimpleName()).append(".html");

		return sb.toString();
	}

	/**
	 * Creates a URL to a class's Javadoc page using the javadoc URL pattern.
	 * @param info the class info
	 * @return the URL
	 */
	private String javadocUrlPattern(ClassInfo info) {
		Matcher m = javadocUrlPatternFieldRegex.matcher(javadocUrlPattern);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String field = m.group(1);
			String replacement;
			switch (field) {
			case "baseUrl":
				replacement = baseUrl;
				break;
			case "full":
				replacement = info.getName().getFullyQualifiedName();
				String delimitor = m.group(3);
				if (delimitor != null) {
					replacement = replacement.replace(".", delimitor);
				}
				break;
			default:
				replacement = "";
				break;
			}

			m.appendReplacement(sb, replacement);
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Gets a list of all classes that are in the library.
	 * @return the class names (in no particular order)
	 * @throws IOException if there's a problem reading the ZIP file
	 */
	public Collection<ClassName> getClassNames() throws IOException {
		final Collection<ClassName> classNames = new ArrayList<>();
		try (FileSystem fs = open()) {
			Path root = fs.getPath("/");
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (isJavadocFile(file)) {
						classNames.add(toClassName(file));
					}
					return FileVisitResult.CONTINUE;
				}

				private boolean isJavadocFile(Path file) {
					String fullPath = file.toString();
					if (!fullPath.endsWith(extension)) {
						return false;
					}

					return !fullPath.equals('/' + infoFileName);
				}

				private ClassName toClassName(Path file) {
					String packageName;
					Path directory = file.getParent();
					if (directory == null) {
						//class is in the default package
						packageName = null;
					} else {
						//e.g. "/java/util"
						packageName = directory.toString();

						//e.g. "java.util"
						packageName = packageName.substring(1).replace('/', '.');
					}

					//e.g. "Map.Entry.xml"
					String fileName = file.getFileName().toString();

					String split[] = fileName.split("\\.");
					List<String> outerClasses = new ArrayList<>();
					for (int i = 0; i < split.length - 2; i++) { //ignore extension and simple name
						outerClasses.add(split[i]);
					}

					String simpleName = split[split.length - 2];

					return new ClassName(packageName, outerClasses, simpleName);
				}
			});
		}
		return Collections.unmodifiableCollection(classNames);
	}

	/**
	 * Gets information about a class.
	 * @param fullName the fully-qualified class name (e.g. "java.lang.String")
	 * @return the class info or null if the class was not found
	 * @throws IOException if there was a problem reading from the ZIP file or
	 * parsing the XML
	 */
	public ClassInfo getClassInfo(String fullName) throws IOException {
		Leaf document;
		try (FileSystem fs = open()) {
			Path path = findClassFile(fs, fullName);
			if (path == null) {
				return null;
			}

			document = parseXml(path);
		}

		return ClassInfoXmlParser.parse(document, this);
	}

	/**
	 * Gets the path to a class's XML file.
	 * @param fs the ZIP file
	 * @param fullName the fully-qualified class name (e.g.
	 * "java.util.Map.Entry")
	 * @return the path to the class's XML file or null if not found
	 */
	private Path findClassFile(FileSystem fs, String fullName) {
		//e.g. "/java/util/Map/Entry.xml", followed by "/java/util/Map.Entry.xml", etc
		String split[] = fullName.split("\\.");
		for (int i = split.length; i > 0; i--) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < i; j++) {
				sb.append('/').append(split[j]);
			}
			for (int j = i; j < split.length; j++) {
				sb.append('.').append(split[j]);
			}
			sb.append(extension);

			Path path = fs.getPath(sb.toString());
			if (Files.exists(path)) {
				return path;
			}
		}

		return null;
	}

	/**
	 * Gets the base URL of this library's Javadocs.
	 * @return the base URL or null if none was defined
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * Gets the name of this library.
	 * @return the name (e.g. "jsoup") or null if none was defined
	 */
	public String getName() {
		return name;
	}

	/**
	 * Gets the version number of this library.
	 * @return the version number (e.g. "1.8.1") or null if none was defined
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Gets the URL to the library's webpage.
	 * @return the URL or null if none was defined
	 */
	public String getProjectUrl() {
		return projectUrl;
	}

	/**
	 * Get the path to the ZIP file.
	 * @return the path to the ZIP file
	 */
	public Path getPath() {
		return file;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + file.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		JavadocZipFile other = (JavadocZipFile) obj;
		if (!file.equals(other.file)) return false;
		return true;
	}

	/**
	 * Parses an XML file.
	 * @param path the path to the file
	 * @return the DOM tree
	 * @throws IOException if there's a problem opening or parsing the file
	 */
	private static Leaf parseXml(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return Leaf.parse(in);
		} catch (SAXException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Opens the ZIP file.
	 * @return the file system handle
	 * @throws IOException if there's a problem opening the ZIP file
	 */
	private FileSystem open() throws IOException {
		return FileSystems.newFileSystem(file, null);
	}
}