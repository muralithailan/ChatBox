package chatbox.command.javadoc;

import java.io.IOException;
import java.util.Collection;

public interface JavadocDao {
	/**
	 * Searches for the fully-qualified name of a class.
	 * @param query can be a simple class name (e.g. "string") or a
	 * fully-qualified class name (e.g. "java.lang.String"). Case does not
	 * matter. If a fully-qualified class name is entered and this DAO is not
	 * aware of that class, then this method will return an empty list.
	 * @return the fully-qualified class name(s) that were found or an empty
	 * list if none were found
	 * @throws IOException if there's a problem searching for the name
	 */
	Collection<String> search(String query) throws IOException;

	/**
	 * Gets the Javadoc info on a class.
	 * @param fullyQualifiedClassName the class's fully-qualified class name
	 * (e.g. "java.lang.String", case-sensitive)
	 * @return the Javadoc info or null if the class was not found
	 * @throws IOException if there's a problem retrieving the info
	 */
	ClassInfo getClassInfo(String fullyQualifiedClassName) throws IOException;
}
