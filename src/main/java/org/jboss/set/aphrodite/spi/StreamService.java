package org.jboss.set.aphrodite.spi;

import java.net.URL;
import java.util.List;

import org.jboss.set.aphrodite.domain.Codebase;
import org.jboss.set.aphrodite.domain.Repository;
import org.jboss.set.aphrodite.domain.Stream;

public interface StreamService {

    /**
     * Returns all streams discovered by this service.
     *
     * @return a list of all streams discovered by this <code>StreamService</code>
     */
    List<Stream> getStreams();

    /**
     * Get a specific <code>Stream</code> object based upon its String name.
     *
     * @param streamName the name of the <code>Stream</code> to be returned.
     * @return Stream the <code>Stream</code> object which corresponds to the specified streamName
     *                if it exists, otherwise null.
     */
    Stream getStream(String streamName);

    /**
     * Find all the url repositories stored in all streams
     * @return list of unique url point to the repositories
     */
    List<URL> findAllRepositories();

    /**
     * Find all the url repositories in the give streams
     * @param streamName the name of the <code>Stream</code> to be returned.
     * @return
     */
    List<URL> findAllRepositoriesInStream(String streamName);

    /**
     * Find all the streams associated to the given repository and codebase
     * @param repository
     * @param codebase
     * @return a list of named streams
     */
    List<Stream> findStreamsBy(Repository repository, Codebase codebase);

    /**
     * Get the component name based on the given repository and codebase.
     * @param repository
     * @param codebase
     * @return the name of the component of this repository. If it does not exist it will return the URL of the repository.
     */
    String findComponentNameBy(Repository repository, Codebase codebase);
}
