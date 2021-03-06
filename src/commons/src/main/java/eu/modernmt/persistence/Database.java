package eu.modernmt.persistence;

import eu.modernmt.model.Domain;

import java.io.Closeable;
import java.util.List;

/**
 * Created by davide on 21/09/16.
 */
public abstract class Database implements Closeable {

    public final Connection getConnection() throws PersistenceException {
        return getConnection(true);
    }

    public abstract Connection getConnection(boolean cached) throws PersistenceException;

    public abstract DomainDAO getDomainDAO(Connection connection);

    public abstract ImportJobDAO getImportJobDAO(Connection connection);

    public abstract void create() throws PersistenceException;

    public abstract boolean exists() throws PersistenceException;

    public abstract String getName() throws PersistenceException;
}
