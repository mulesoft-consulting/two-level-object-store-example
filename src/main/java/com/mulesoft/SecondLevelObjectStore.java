package com.mulesoft;

import java.io.Serializable;
import java.util.concurrent.locks.Lock;

import org.mule.api.MuleContext;
import org.mule.api.context.MuleContextAware;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.store.ObjectDoesNotExistException;
import org.mule.api.store.ObjectStore;
import org.mule.api.store.ObjectStoreException;
import org.mule.config.i18n.CoreMessages;
import org.mule.util.lock.LockFactory;
import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SecondLevelObjectStore<T extends Serializable> implements ObjectStore<T>, MuleContextAware {

	protected final Log logger = LogFactory.getLog(this.getClass());

	private ObjectStore<T> firstLevel;
	private ObjectStore<T> secondLevel;

	private MuleContext muleContext;

	@Override
	public boolean contains(Serializable key) throws ObjectStoreException {
		logger.debug("Looking for key: " + key);
		return (firstLevel.contains(key) || secondLevel.contains(key));
	}

	@Override
	public void store(Serializable key, T value) throws ObjectStoreException {

		Lock lock = muleContext.getLockFactory().createLock(new String(SerializationUtils.serialize(key)));
		logger.debug("Caching: " + key);
		try {

			lock.lock();

			if (!firstLevel.contains(key)) {
				firstLevel.store(key, value);
			}

			if (!secondLevel.contains(key)) {
				secondLevel.store(key, value);
			}
		} catch (Throwable t) {
			throw new ObjectStoreException(t);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public T retrieve(Serializable key) throws ObjectStoreException {
		try {
			if (firstLevel.contains(key)) {
				return getAndSynchronize(key, firstLevel, secondLevel);
			} else if (secondLevel.contains(key)) {
				return getAndSynchronize(key, secondLevel, secondLevel);
			} else {
				throw new ObjectDoesNotExistException(CoreMessages.objectNotFound(key));
			}
		} catch (Throwable t) {
			throw new ObjectStoreException(t);
		}
	}

	@Override
	public T remove(Serializable key) throws ObjectStoreException {

		Lock lock =  muleContext.getLockFactory().createLock(new String(SerializationUtils.serialize(key)));

		T result1 = null;
		T result2 = null;

		try {
			if (firstLevel.contains(key)) {
				result1 = (T) firstLevel.remove(key);
			}

			if (secondLevel.contains(key)) {
				result2 = (T) secondLevel.remove(key);
			}

			if (result1 == null && result2 == null) {
				throw new ObjectDoesNotExistException(CoreMessages.objectNotFound(key));
			}

			return result1 != null ? result1 : result2;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean isPersistent() {
		return (firstLevel.isPersistent() && secondLevel.isPersistent());
	}

	@Override
	public void clear() throws ObjectStoreException {
		throw new UnsupportedOperationException();
	}

	T getAndSynchronize(Serializable key, ObjectStore<T> store1, ObjectStore<T> store2) throws Exception {
		T result = (T) store1.retrieve(key);
		if (!store2.contains(key)) {
			store2.store(key, result);
		}
		return result;
	}

	public ObjectStore<T> getFirstLevel() {
		return firstLevel;
	}

	public void setFirstLevel(ObjectStore<T> firstLevel) {
		this.firstLevel = firstLevel;
	}

	public ObjectStore<T> getSecondLevel() {
		return secondLevel;
	}

	public void setSecondLevel(ObjectStore<T> secondLevel) {
		this.secondLevel = secondLevel;
	}

	@Override
	public void setMuleContext(MuleContext context) {
		this.muleContext = context;
	}

}
