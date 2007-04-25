/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.test.impl.service.data;

import com.sun.sgs.app.DataManager;
import com.sun.sgs.app.TransactionAbortedException;
import com.sun.sgs.impl.kernel.StandardProperties;
import com.sun.sgs.impl.service.data.DataServiceImpl;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.service.DataService;
import com.sun.sgs.test.util.DummyComponentRegistry;
import com.sun.sgs.test.util.DummyManagedObject;
import com.sun.sgs.test.util.DummyTransaction;
import com.sun.sgs.test.util.DummyTransactionProxy;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;

/** Test concurrent operation of the data service. */
@SuppressWarnings("hiding")
public class TestDataServiceConcurrency extends TestCase {

    /** Logger for this test. */
    private static final LoggerWrapper logger =
	new LoggerWrapper(
	    Logger.getLogger(TestDataServiceConcurrency.class.getName()));

    /** The name of the DataStoreImpl class. */
    private static final String DataStoreImplClass =
	"com.sun.sgs.impl.service.data.store.DataStoreImpl";

    /** Whether to only perform read-only operations during the test. */
    static final boolean readOnly = Boolean.getBoolean("test.read.only");

    /** The number of operations to perform. */
    protected int operations = Integer.getInteger("test.operations", 10000);

    /** The maximum number of objects to allocate per thread. */
    protected int objects = Integer.getInteger("test.objects", 1000);

    /** The number of objects to allocate as a buffer between threads. */
    final int objectsBuffer = 500;

    /** The number of concurrent threads. */
    protected int threads = Integer.getInteger("test.threads", 2);

    /** The number of times to repeat the timing. */
    protected int repeat = Integer.getInteger("test.repeat", 1);

    /** The transaction proxy. */
    final DummyTransactionProxy txnProxy = new DummyTransactionProxy();

    /** Set when the test passes. */
    protected boolean passed;

    /** A per-test database directory. */
    private String directory = System.getProperty("test.directory");

    /**
     * The exception thrown by one of the threads, or null if none of the
     * threads have failed.
     */
    private Throwable failure;

    /** The total number of aborts seen by the various threads. */
    private int aborts;

    /** The number of threads that are done. */
    private int done;

    /** Properties for creating services. */
    protected Properties props;

    /** The service to test. */
    private DataService service;

    /** Creates the test. */
    public TestDataServiceConcurrency(String name) {
	super(name);
    }

    /** Prints the name of the test case. */
    protected void setUp() throws Exception {
	System.err.println("Testcase: " + getName());
	System.err.println(
	    "Parameters:" +
	    "\n  test.operations=" + operations +
	    "\n  test.objects=" + objects +
	    "\n  test.threads=" + threads);
	props = createProperties(
	    DataStoreImplClass + ".directory", createDirectory(),
	    StandardProperties.APP_NAME, "TestDataServiceConcurrency");
    }

    /** Sets passed if the test passes. */
    protected void runTest() throws Throwable {
	super.runTest();
	passed = true;
    }

    /** Deletes the directory if the test passes. */
    protected void tearDown() throws Exception {
	if (service != null) {
	    try {
		shutdown();
	    } catch (RuntimeException e) {
		if (passed) {
		    throw e;
		} else {
		    e.printStackTrace();
		}
	    }
	}
	if (passed) {
	    deleteDirectory(directory);
	}
    }

    /** Shuts down the service. */
    protected boolean shutdown() {
	return service.shutdown();
    }

    /* -- Tests -- */

    public void testConcurrency() throws Throwable {
	DummyComponentRegistry componentRegistry =
	    new DummyComponentRegistry();
	service = getDataService(props, componentRegistry);
	DummyTransaction txn = new DummyTransaction();
	txnProxy.setCurrentTransaction(txn);
	service.configure(componentRegistry, txnProxy);
	componentRegistry.setComponent(DataManager.class, service);
	componentRegistry.registerAppContext();
	txn.commit();
	int perThread = objects + objectsBuffer;
	for (int t = 0; t < threads; t++) {
	    txn = new DummyTransaction();
	    txnProxy.setCurrentTransaction(txn);
	    int start = t * perThread;
	    for (int i = 0; i < perThread; i++) {
		if (i > 0 && i % 100 == 0) {
		    txn.commit();
		    txn = new DummyTransaction();
		    txnProxy.setCurrentTransaction(txn);
		}
		service.setBinding(
		    getObjectName(start + i), new DummyManagedObject());
	    }
	    txn.commit();
	}
	for (int r = 0; r < repeat; r++) {
	    aborts = 0;
	    done = 0;
	    long start = System.currentTimeMillis();
	    for (int i = 0; i < threads; i++) {
		new OperationThread(i, service, txnProxy);
	    }
	    while (true) {
		synchronized (this) {
		    if (failure != null || done >= threads) {
			break;
		    }
		    try {
			wait();
		    } catch (InterruptedException e) {
			failure = e;
			break;
		    }
		}
	    }
	    long stop = System.currentTimeMillis();
	    if (failure != null) {
		throw failure;
	    }
	    long ms = stop - start;
	    double s = (stop - start) / 1000.0d;
	    System.err.println(
		"Time: " + ms + " ms\n" +
		"Aborts: " + aborts + "\n" +
		"Ops per second: " + Math.round((threads * operations) / s));
	}
    }

    /* -- Other methods and classes -- */

    /**
     * Notes that a thread has completed successfully, and records the number
     * of aborts that occurred in the thread.
     */
    synchronized void threadDone(int aborts) {
	done++;
	this.aborts += aborts;
	notifyAll();
    }

    /** Notes that a thread has failed with the specified exception. */
    synchronized void threadFailed(Throwable failure) {
	this.failure = failure;
	notifyAll();
    }

    /** Performs random operations in a separate thread. */
    class OperationThread extends Thread {
	private final DataService service;
	private final DummyTransactionProxy txnProxy;
	private final int id;
	private final Random random = new Random();
	private DummyTransaction txn;
	private int aborts;

	OperationThread(
	    int id, DataService service, DummyTransactionProxy txnProxy)
	{
	    super("OperationThread" + id);
	    this.service = service;
	    this.txnProxy = txnProxy;
	    this.id = id;
	    start();
	}

	public void run() {
	    try {
		createTxn();
		for (int i = 0; i < operations; i++) {
		    if (i % 1000 == 0) {
			System.err.println(this + ": Operation " + i);
		    }
		    while (true) {
			try {
			    op(i);
			    break;
			} catch (TransactionAbortedException e) {
			    if (logger.isLoggable(Level.FINE)) {
				logger.log(Level.FINE, "{0}: {1}", this, e);
			    }
			    aborts++;
			    createTxn();
			}
		    }
		}
		txn.abort(null);
		threadDone(aborts);
	    } catch (Throwable t) {
		try {
		    txn.abort(null);
		} catch (RuntimeException e) {
		}
		threadFailed(t);
	    }
	}

	private void op(int i) throws Exception {
	    if (random.nextInt(10) == 0) {
		DummyTransaction t = txn;
		txn = null;
		t.commit();
		createTxn();
	    }
	    int start = id * (objects + objectsBuffer);
	    String name1 = getObjectName(start + random.nextInt(objects));
	    DummyManagedObject obj1 =
		service.getBinding(name1, DummyManagedObject.class);
	    String name2 = getObjectName(start + random.nextInt(objects));
	    DummyManagedObject obj2 =
		service.getBinding(name2, DummyManagedObject.class);
	    if (!readOnly && random.nextInt(4) == 0) {
		service.setBinding(name1, obj2);
		service.setBinding(name2, obj1);
	    }
	}

	private void createTxn() {
	    txn = new DummyTransaction();
	    txnProxy.setCurrentTransaction(txn);
	}
    }

    /** Creates a per-test directory. */
    private String createDirectory() throws IOException {
	if (directory != null) {
	    new File(directory).mkdir();
	} else {
	    File dir = File.createTempFile(getName(), "dbdir");
	    if (!dir.delete()) {
		throw new RuntimeException("Problem deleting file: " + dir);
	    }
	    if (!dir.mkdir()) {
		throw new RuntimeException(
		    "Failed to create directory: " + dir);
	    }
	    directory = dir.getPath();
	}
	return directory;
    }

    /** Deletes the specified directory, if it exists. */
    private static void deleteDirectory(String directory) {
	File dir = new File(directory);
	if (dir.exists()) {
	    for (File f : dir.listFiles()) {
		if (!f.delete()) {
		    throw new RuntimeException("Failed to delete file: " + f);
		}
	    }
	    if (!dir.delete()) {
		throw new RuntimeException(
		    "Failed to delete directory: " + dir);
	    }
	}
    }

    /** Creates a property list with the specified keys and values. */
    private static Properties createProperties(String... args) {
	Properties props = new Properties();
	if (args.length % 2 != 0) {
	    throw new RuntimeException("Odd number of arguments");
	}
	for (int i = 0; i < args.length; i += 2) {
	    props.setProperty(args[i], args[i + 1]);
	}
	/* Include system properties and allow them to override */
	props.putAll(System.getProperties());
	return props;
    }

    /** Returns the data service to test. */
    protected DataService getDataService(
	Properties props, ComponentRegistry componentRegistry)
	throws Exception
    {
	return new DataServiceImpl(props, componentRegistry);
    }

    /** Returns the binding name to use for the i'th object. */
    private static String getObjectName(int i) {
	return String.format("obj-%08d", i);
    }
}
