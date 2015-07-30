/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.neo4j.transaction.impl;

import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.hibernate.ogm.datastore.neo4j.impl.Neo4jDatastoreProvider;
import org.hibernate.ogm.transaction.impl.ForwardingTransactionCoordinator;
import org.hibernate.ogm.transaction.impl.ForwardingTransactionDriver;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;

/**
 * A {@link TransactionCoordinator} for Neo4j.
 *
 * Note that during a JTA transaction Neo4j {@link Transaction} are
 * synchronized using the {@link Synchronization} interface. A commit to the Neo4j transaction will happen before the
 * end of the JTA transaction, meaning that it won't be possible to rollback if an error happen after succesful commit
 * to the db.
 *
 * @author Davide D'Alto
 */
public class Neo4jTransactionCoordinator extends ForwardingTransactionCoordinator {

	private final GraphDatabaseService graphDB;
	private Transaction tx = null;

	public Neo4jTransactionCoordinator(TransactionCoordinator delegate, Neo4jDatastoreProvider graphDb) {
		super( delegate );
		this.graphDB = graphDb.getDataBase();
	}

	@Override
	public TransactionDriver getTransactionDriverControl() {
		TransactionDriver driver = super.getTransactionDriverControl();
		return new Neo4jTransactionDriver( driver );
	}

	@Override
	public void explicitJoin() {
		super.explicitJoin();
		join();
	}

	@Override
	public void pulse() {
		super.pulse();
		join();
	}

	private void join() {
		if ( tx == null && delegate.isActive() && delegate.getTransactionCoordinatorBuilder().isJta() ) {
			tx = graphDB.beginTx();
			delegate.getLocalSynchronizations().registerSynchronization( new Neo4jSynchronization() );
		}
	}

	private void success() {
		if ( tx != null ) {
			tx.success();
			close();
		}
	}

	private void failure() {
		if ( tx != null ) {
			tx.failure();
			close();
		}
	}

	private void close() {
		try {
			tx.close();
		}
		catch (Exception e) {
			throw e;
		}
		finally {
			tx = null;
		}
	}

	private class Neo4jSynchronization implements Synchronization {

		@Override
		public void beforeCompletion() {
			TransactionStatus status = delegate.getTransactionDriverControl().getStatus();
			if ( status == TransactionStatus.MARKED_ROLLBACK ) {
				failure();
			}
			else {
				success();
			}
		}

		@Override
		public void afterCompletion(int status) {
			if ( tx != null ) {
				if ( status != Status.STATUS_COMMITTED ) {
					failure();
				}
				else {
					success();
				}
			}
		}
	}

	private class Neo4jTransactionDriver extends ForwardingTransactionDriver {

		public Neo4jTransactionDriver(TransactionDriver delegate) {
			super( delegate );
		}

		@Override
		public void begin() {
			super.begin();
			if ( tx == null ) {
				tx = graphDB.beginTx();
			}
		}

		@Override
		public void commit() {
			try {
				super.commit();
				success();
			}
			catch (Exception e) {
				try {
					failure();
				}
				catch (Exception rollbackEx) {
				}
				throw e;
			}
		}

		@Override
		public void rollback() {
			try {
				super.rollback();
			}
			finally {
				failure();
			}
		}

	}
}
