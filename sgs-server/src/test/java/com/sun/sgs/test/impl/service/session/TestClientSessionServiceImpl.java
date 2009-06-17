/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.test.impl.service.session;

import com.sun.sgs.app.AppContext;
import com.sun.sgs.app.AppListener;
import com.sun.sgs.app.ClientSession;
import com.sun.sgs.app.ClientSessionListener;
import com.sun.sgs.app.DataManager;
import com.sun.sgs.app.Delivery;
import com.sun.sgs.app.ExceptionRetryStatus;
import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.app.MessageRejectedException;
import com.sun.sgs.app.NameNotBoundException;
import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.app.util.ManagedSerializable;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.kernel.StandardProperties;
import com.sun.sgs.impl.protocol.simple.SimpleSgsProtocolAcceptor;
import com.sun.sgs.impl.service.nodemap.DirectiveNodeAssignmentPolicy;
import com.sun.sgs.impl.service.session.ClientSessionServer;
import com.sun.sgs.impl.service.session.ClientSessionServiceImpl;
import com.sun.sgs.impl.service.session.ClientSessionWrapper;
import com.sun.sgs.impl.sharedutil.HexDumper;
import com.sun.sgs.impl.sharedutil.MessageBuffer;
import com.sun.sgs.impl.util.AbstractService.Version;
import com.sun.sgs.kernel.TransactionScheduler;
import com.sun.sgs.protocol.simple.SimpleSgsProtocol;
import com.sun.sgs.service.ClientSessionStatusListener;
import com.sun.sgs.service.ClientSessionService;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.SimpleCompletionHandler;
import com.sun.sgs.test.util.DummyClient;
import com.sun.sgs.test.util.SgsTestNode;
import com.sun.sgs.test.util.SimpleTestIdentityAuthenticator;
import com.sun.sgs.test.util.TestAbstractKernelRunnable;
import com.sun.sgs.tools.test.FilteredJUnit3TestRunner;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.junit.runner.RunWith;

import static com.sun.sgs.test.util.UtilProperties.createProperties;

@RunWith(FilteredJUnit3TestRunner.class)
public class TestClientSessionServiceImpl extends TestCase {

    /** If this property is set, then only run the single named test method. */
    private static final String testMethod = System.getProperty("test.method");

    /**
     * Specify the test suite to include all tests, or just a single method if
     * specified.
     */
    public static TestSuite suite() throws Exception {
	if (testMethod == null) {
	    return new TestSuite(TestClientSessionServiceImpl.class);
	}
	TestSuite suite = new TestSuite();
	suite.addTest(new TestClientSessionServiceImpl(testMethod));
	return suite;
    }

    private static final String APP_NAME = "TestClientSessionServiceImpl";
    
    private static final String LOGIN_FAILED_MESSAGE = "login failed";

    private static final int WAIT_TIME = 5000;

    /** Number of managed objects per client session: 4 (ClientSessionImpl,
     * ClientSessionWrapper, EventQueue, ManagedQueue).
     */
    private static final int MANAGED_OBJECTS_PER_SESSION = 4;

    private static final String RETURN_NULL = "return null";

    private static final String NON_SERIALIZABLE = "non-serializable";

    private static final String THROW_RUNTIME_EXCEPTION =
	"throw RuntimeException";

    private static final String DISCONNECT_THROWS_NONRETRYABLE_EXCEPTION =
	"disconnect throws non-retryable exception";

    private static final String SESSION_PREFIX =
	"com.sun.sgs.impl.service.session.impl";

    private static final String SESSION_NODE_PREFIX =
	"com.sun.sgs.impl.service.session.node";

    private static final String LISTENER_PREFIX =
	"com.sun.sgs.impl.service.session.listener";

    private static final String NODE_PREFIX =
	"com.sun.sgs.impl.service.watchdog.node";

    /** The ClientSession service properties. */
    private static final Properties serviceProps =
	createProperties(
	    StandardProperties.APP_NAME, APP_NAME,
            com.sun.sgs.impl.transport.tcp.TcpTransport.LISTEN_PORT_PROPERTY,
	    "20000");

    /** The node that creates the servers. */
    private SgsTestNode serverNode;

    /** Any additional nodes, keyed by node host name (for tests
     * needing more than one node). */
    private Map<String,SgsTestNode> additionalNodes;

    private boolean allowNewLogin = false;

    /** Version information from ClientSessionServiceImpl class. */
    private final String VERSION_KEY;
    private final int MAJOR_VERSION;
    private final int MINOR_VERSION;
    
    /** If {@code true}, shuts off some printing during performance tests. */
    private boolean isPerformanceTest = false;
    
    /** The transaction scheduler. */
    private TransactionScheduler txnScheduler;

    /** The owner for tasks I initiate. */
    private Identity taskOwner;

    /** The shared data service. */
    private DataService dataService;

    /** The test clients, keyed by client session ID. */
    private static Map<BigInteger, DummyClient> dummyClients;

    private static volatile RuntimeException receivedMessageException = null;
    
    private static Field getField(Class cl, String name) throws Exception {
	Field field = cl.getDeclaredField(name);
	field.setAccessible(true);
	return field;
    }

    /** Constructs a test instance. */
    public TestClientSessionServiceImpl(String name) throws Exception {
	super(name);
	Class cl = ClientSessionServiceImpl.class;
	VERSION_KEY = (String) getField(cl, "VERSION_KEY").get(null);
	MAJOR_VERSION = getField(cl, "MAJOR_VERSION").getInt(null);
	MINOR_VERSION = getField(cl, "MINOR_VERSION").getInt(null);
    }

    protected void setUp() throws Exception {
        System.err.println("Testcase: " + getName());
        setUp(null, true);
    }

    /** Creates and configures the session service. */
    protected void setUp(Properties props, boolean clean) throws Exception {
	if (props == null) {
	    props = 
                SgsTestNode.getDefaultProperties(APP_NAME, null, 
						 DummyAppListener.class);
	}
        props.setProperty(StandardProperties.AUTHENTICATORS, 
                      "com.sun.sgs.test.util.SimpleTestIdentityAuthenticator");
	props.setProperty("com.sun.sgs.impl.service.nodemap.policy.class",
			  DirectiveNodeAssignmentPolicy.class.getName());
	serverNode = 
                new SgsTestNode(APP_NAME, DummyAppListener.class, props, clean);

        txnScheduler = 
            serverNode.getSystemRegistry().
            getComponent(TransactionScheduler.class);
        taskOwner = serverNode.getProxy().getCurrentOwner();

        dataService = serverNode.getDataService();
        dummyClients = new HashMap<BigInteger, DummyClient>();
	receivedMessageException = null;
    }

    /** 
     * Add additional nodes.  We only do this as required by the tests. 
     *
     * @param hosts contains a host name for each additional node
     */
    private void addNodes(String... hosts) throws Exception {
        // Create the other nodes
	if (additionalNodes == null) {
	    additionalNodes = new HashMap<String, SgsTestNode>();
	}

        for (String host : hosts) {
            Properties props = SgsTestNode.getDefaultProperties(
                APP_NAME, serverNode, DummyAppListener.class);
	    props.setProperty(StandardProperties.AUTHENTICATORS, 
                "com.sun.sgs.test.util.SimpleTestIdentityAuthenticator");
            props.put("com.sun.sgs.impl.service.watchdog.client.host", host);
	    if (allowNewLogin) {
		props.setProperty(
		    "com.sun.sgs.impl.service.session.allow.new.login", "true");
	    }
	    
            SgsTestNode node =
                    new SgsTestNode(serverNode, DummyAppListener.class, props);
            additionalNodes.put(host, node);
        }
    }

    /** Sets passed if the test passes. */
    protected void runTest() throws Throwable {
	super.runTest();
        Thread.sleep(100);
    }

    protected void tearDown() throws Exception {
        tearDown(true);
    }

    protected void tearDown(boolean clean) throws Exception {
        Thread.sleep(100);
	if (additionalNodes != null) {
            for (SgsTestNode node : additionalNodes.values()) {
                node.shutdown(false);
            }
            additionalNodes = null;
        }
        serverNode.shutdown(clean);
        serverNode = null;
    }

    // -- Test constructor --

    public void testConstructorNullProperties() throws Exception {
	try {
	    new ClientSessionServiceImpl(
		null, serverNode.getSystemRegistry(),
		serverNode.getProxy());
	    fail("Expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }

    public void testConstructorNullComponentRegistry() throws Exception {
	try {
	    new ClientSessionServiceImpl(serviceProps, null,
					 serverNode.getProxy());
	    fail("Expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }

    public void testConstructorNullTransactionProxy() throws Exception {
	try {
	    new ClientSessionServiceImpl(serviceProps,
					 serverNode.getSystemRegistry(), null);
	    fail("Expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }

    public void testConstructorNoAppName() throws Exception {
	try {
	    new ClientSessionServiceImpl(
		new Properties(), serverNode.getSystemRegistry(),
		serverNode.getProxy());
	    fail("Expected IllegalArgumentException");
	} catch (IllegalArgumentException e) {
	    System.err.println(e);
	}
    }

    public void testConstructorNoPort() throws Exception {
        Properties props =
            createProperties(StandardProperties.APP_NAME, APP_NAME);
        new ClientSessionServiceImpl(
            props, serverNode.getSystemRegistry(),
            serverNode.getProxy());
    }

    public void testConstructorDisconnectDelayTooSmall() throws Exception {
	try {
	    Properties props =
		createProperties(
		    StandardProperties.APP_NAME, APP_NAME,
                    SimpleSgsProtocolAcceptor.DISCONNECT_DELAY_PROPERTY, "199");
	    new ClientSessionServiceImpl(
		props, serverNode.getSystemRegistry(),
		serverNode.getProxy());
	    fail("Expected IllegalArgumentException");
	} catch (IllegalArgumentException e) {
	    System.err.println(e);
	}
    }

    public void testConstructedVersion() throws Exception {
	txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    Version version = (Version)
			dataService.getServiceBinding(VERSION_KEY);
		    if (version.getMajorVersion() != MAJOR_VERSION ||
			version.getMinorVersion() != MINOR_VERSION)
		    {
			fail("Expected service version (major=" +
			     MAJOR_VERSION + ", minor=" + MINOR_VERSION +
			     "), got:" + version);
		    }
		}}, taskOwner);
    }
    
    public void testConstructorWithCurrentVersion() throws Exception {
	txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    Version version = new Version(MAJOR_VERSION, MINOR_VERSION);
		    dataService.setServiceBinding(VERSION_KEY, version);
		}}, taskOwner);

	new ClientSessionServiceImpl(
	    serviceProps, serverNode.getSystemRegistry(),
	    serverNode.getProxy());
    }

    public void testConstructorWithMajorVersionMismatch() throws Exception {
	txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    Version version =
			new Version(MAJOR_VERSION + 1, MINOR_VERSION);
		    dataService.setServiceBinding(VERSION_KEY, version);
		}}, taskOwner);

	try {
	    new ClientSessionServiceImpl(
		serviceProps, serverNode.getSystemRegistry(),
		serverNode.getProxy());
	    fail("Expected IllegalStateException");
	} catch (IllegalStateException e) {
	    System.err.println(e);
	}
    }

    public void testConstructorWithMinorVersionMismatch() throws Exception {
	txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    Version version =
			new Version(MAJOR_VERSION, MINOR_VERSION + 1);
		    dataService.setServiceBinding(VERSION_KEY, version);
		}}, taskOwner);

	try {
	    new ClientSessionServiceImpl(
		serviceProps, serverNode.getSystemRegistry(),
		serverNode.getProxy());
	    fail("Expected IllegalStateException");
	} catch (IllegalStateException e) {
	    System.err.println(e);
	}
    }

    // -- Test addSessionStatusListener --

    public void testRegisterSessionStatusListenerNullArg() {
	try {
	    serverNode.getClientSessionService().
		addSessionStatusListener(null);
	    fail("expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }

    public void testRegisterSessionStatusListenerInTxn()
	throws Exception
    {
	try {
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    serverNode.getClientSessionService().
			addSessionStatusListener(
			    new DummyStatusListener());
		}}, taskOwner);
	    fail("expected IllegalStateException");
	} catch (IllegalStateException e) {
	    System.err.println(e);
	}
    }
    
    public void testRegisterSessionStatusListenerNoTxn() {
	serverNode.getClientSessionService().
	    addSessionStatusListener(new DummyStatusListener());
    }

    // -- Test getSessionProtocol --

    public void testGetSessionProtocolNullArg() {
	try {
	    serverNode.getClientSessionService(). getSessionProtocol(null);
	    fail("expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }
    
    public void testGetSessionProtocolInTxn()
	throws Exception
    {
	try {
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    serverNode.getClientSessionService().
			getSessionProtocol(new BigInteger(1, new byte[] {0}));
		}}, taskOwner);
	    fail("expected IllegalStateException");
	} catch (IllegalStateException e) {
	    System.err.println(e);
	}
    }

    public void testGetSessionProtocolNoTxn() {
	assertNull(serverNode.getClientSessionService().
		   getSessionProtocol(new BigInteger(1, new byte[] {0})));
    }
    
    // -- Test connecting, logging in, logging out with server -- 

    public void testConnection() throws Exception {
	DummyClient client = createDummyClient("foo");
	try {
	    client.connect(serverNode.getAppPort());
	} catch (Exception e) {
	    System.err.println("Exception: " + e);
	    Throwable t = e.getCause();
	    System.err.println("caused by: " + t);
	    System.err.println("detail message: " + t.getMessage());
	    throw e;
	    
	} finally {
	    client.disconnect();
	}
    }

    public void testLoginSuccess() throws Exception {
	DummyClient client = createDummyClient("success");
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	} finally {
            client.disconnect();
	}
    }

    public void testLoginRedirect() throws Exception {
	int serverAppPort = serverNode.getAppPort();
	String[] hosts = new String[] { "one", "two", "three", "four"};
	String[] users = new String[] { "sleepy", "bashful", "dopey", "doc" };
	Set<DummyClient> clients = new HashSet<DummyClient>();
	addNodes(hosts);
	boolean failed = false;
	int redirectCount = 0;
	try {
	    for (String user : users) {
		DummyClient client = createDummyClient(user);
		client.connect(serverAppPort);
		if (client.login()) {
		    if (client.getConnectPort() != serverAppPort) {
			// login redirected
			redirectCount++;
		    }
		} else {
		    failed = true;
		    System.err.println("login for user: " + user + " failed");
		}
		clients.add(client);
	    }
	    
	    int expectedRedirects = users.length;
	    if (redirectCount != expectedRedirects) {
		failed = true;
		System.err.println("Expected " + expectedRedirects +
				   " redirects, got " + redirectCount);
	    } else {
		System.err.println(
		    "Number of redirected users: " + redirectCount);
	    }
	    
	    if (failed) {
		fail("test failed (see output)");
	    }
	    
	} finally {
	    for (DummyClient client : clients) {
		try {
		    client.disconnect();
		} catch (Exception e) {
		    System.err.println(
			"Exception disconnecting client: " + client);
		}
	    }
	}
	
    }
    
    public void testSendBeforeLoginComplete() throws Exception {
	DummyClient client = createDummyClient("dummy");
	try {
	    client.connect(serverNode.getAppPort());
	    client.login(false);
	    client.sendMessagesInSequence(1, 0);
	} finally {
            client.disconnect();
	}
    }

    public void testSendAfterLoginComplete() throws Exception {
	DummyClient client = createDummyClient("dummy");
	try {
	    client.connect(serverNode.getAppPort());
	    client.login(true);
	    client.sendMessagesInSequence(1, 1);
	} finally {
            client.disconnect();
	}
    }

    public void testLoginSuccessAndNotifyLoggedInCallback() throws Exception {
	String name = "success";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    if (SimpleTestIdentityAuthenticator.allIdentities.
                    getNotifyLoggedIn(name)) {
		System.err.println(
		    "notifyLoggedIn invoked for identity: " + name);
	    } else {
		fail("notifyLoggedIn not invoked for identity: " + name);
	    }
	} finally {
            client.disconnect();
	}
    }

    public void testLoggedInReturningNonSerializableClientSessionListener()
	throws Exception
    {
	DummyClient client = createDummyClient(NON_SERIALIZABLE);
	try {
	    client.connect(serverNode.getAppPort());
	    assertFalse(client.login());
	    assertFalse(SimpleTestIdentityAuthenticator.allIdentities.
			getNotifyLoggedIn(NON_SERIALIZABLE));
	} finally {
	    client.disconnect();
	}
    }

    public void testLoggedInReturningNullClientSessionListener()
	throws Exception
    {
	DummyClient client = createDummyClient(RETURN_NULL);
	try {
	    client.connect(serverNode.getAppPort());
	    assertFalse(client.login());
	    assertFalse(SimpleTestIdentityAuthenticator.allIdentities.
		        getNotifyLoggedIn(RETURN_NULL));
	} finally {
	    client.disconnect();
	}
    }

    public void testLoggedInThrowingRuntimeException()
	throws Exception
    {
	DummyClient client = createDummyClient(THROW_RUNTIME_EXCEPTION);
	try {
	    client.connect(serverNode.getAppPort());
	    assertFalse(client.login());
	    assertFalse(SimpleTestIdentityAuthenticator.allIdentities.
			getNotifyLoggedIn(THROW_RUNTIME_EXCEPTION));
	} finally {
	    client.disconnect();
	}
    }

    public void testLoginTwiceBlockUser() throws Exception {
	String name = "dummy";
	DummyClient client1 = createDummyClient(name);
	DummyClient client2 = createDummyClient(name);
	int port = serverNode.getAppPort();
	try {
	    assertTrue(client1.connect(port).login());
	    assertFalse(client2.connect(port).login());
	} finally {
	    client1.disconnect();
	    client2.disconnect();
	}
    }

    public void testLoginTwicePreemptUser() throws Exception {
	// Set up ClientSessionService to preempt user if same user logs in
	tearDown(false);
	Properties props =
	    SgsTestNode.getDefaultProperties(APP_NAME, null,
					     DummyAppListener.class);
	props.setProperty(
 	    "com.sun.sgs.impl.service.session.allow.new.login", "true");
	setUp(props, false);
	String name = "dummy";
	
	DummyClient client1 = createDummyClient(name);
	DummyClient client2 = createDummyClient(name);
	int port = serverNode.getAppPort();
	try {
	    assertTrue(client1.connect(port).login());
	    Thread.sleep(100);
	    assertTrue(client2.connect(port).login());
	    client1.checkDisconnectedCallback(false);
	    assertTrue(client2.isConnected());
	    
	} finally {
	    client1.disconnect();
	    client2.disconnect();
	}
    }
    
    public void testDisconnectFromServerAfterLogout() throws Exception {
	final String name = "logout";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    client.logout();
	    assertTrue(client.isConnected());
	    assertTrue(client.waitForDisconnect());
	} finally {
	    client.disconnect();
	}
    }

    public void testLogoutRequestAndDisconnectedCallback() throws Exception {
	final String name = "logout";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    checkBindings(1);
	    client.logout();
	    client.checkDisconnectedCallback(true);
	    checkBindings(0);
	    // check that client session was removed after disconnected callback
	    // returned 
            txnScheduler.runTask(new TestAbstractKernelRunnable() {
                public void run() {
		    try {
			dataService.getBinding(name);
			fail("expected ObjectNotFoundException: " +
			     "object not removed");
		    } catch (ObjectNotFoundException e) {
		    }
                }
             }, taskOwner);
	} catch (InterruptedException e) {
	    e.printStackTrace();
	    fail("testLogout interrupted");
	} finally {
	    client.disconnect();
	}
    }

    public void testDisconnectedCallbackThrowingNonRetryableException()
	throws Exception
    {
	DummyClient client =
	    createDummyClient(DISCONNECT_THROWS_NONRETRYABLE_EXCEPTION);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    checkBindings(1);
	    client.logout();
	    client.checkDisconnectedCallback(true);
	    // give scheduled task a chance to clean up...
	    Thread.sleep(250);
	    checkBindings(0);	    
	} finally {
	    client.disconnect();
	}
    }

    public void testLogoutAndNotifyLoggedOutCallback() throws Exception {
	String name = "logout";
	DummyClient client = createDummyClient(name);
	try {
	    assertTrue(client.connect(serverNode.getAppPort()).login());
	    client.logout();
	    if (SimpleTestIdentityAuthenticator.allIdentities.
                    getNotifyLoggedIn(name)) {
		System.err.println(
		    "notifyLoggedIn invoked for identity: " + name);
	    } else {
		fail("notifyLoggedIn not invoked for identity: " + name);
	    }
	    if (SimpleTestIdentityAuthenticator.allIdentities.
                    getNotifyLoggedOut(name)) {
		System.err.println(
		    "notifyLoggedOut invoked for identity: " + name);
	    } else {
		fail("notifyLoggedOut not invoked for identity: " + name);
	    }
	} finally {
            client.disconnect();
	}
    }

    public void testNotifyClientSessionListenerAfterCrash() throws Exception {
	int numClients = 4;
	try {
	    List<String> nodeKeys = getServiceBindingKeys(NODE_PREFIX);
	    System.err.println("Node keys: " + nodeKeys);
	    if (nodeKeys.isEmpty()) {
		fail("no node keys");
	    } else if (nodeKeys.size() > 1) {
		fail("more than one node key");
	    }

	    int appPort = serverNode.getAppPort();
	    for (int i = 0; i < numClients; i++) {
		 // Create half of the clients with a name that starts with
		 // "badClient" which will cause the associated session's
		 // ClientSessionListener's 'disconnected' method to throw a
		 // non-retryable exception.  We want to make sure that all the
		 // client sessions are cleaned up after a crash, even if
		 // invoking a session's listener's 'disconnected' callback
		 // throws a non-retryable exception.
		String name = (i % 2 == 0) ? "client" : "badClient";
		DummyClient client = createDummyClient(name + String.valueOf(i));
		assertTrue(client.connect(appPort).login());
	    }
	    checkBindings(numClients);

            // Simulate "crash"
            tearDown(false);
	    String failedNodeKey = nodeKeys.get(0);
            setUp(null, false);

	    for (DummyClient client : dummyClients.values()) {
		client.checkDisconnectedCallback(false);
	    }
	    
	    // Wait to make sure that bindings and node key are cleaned up.
	    // Some extra time is needed when a ClientSessionListener throws a
	    // non-retryable exception because a separate task is scheduled to
	    // clean up the client session and bindings.
	    Thread.sleep(WAIT_TIME);
	    
	    System.err.println("check for session bindings being removed.");
	    checkBindings(0);
	    nodeKeys = getServiceBindingKeys(NODE_PREFIX);
	    System.err.println("Node keys: " + nodeKeys);
	    if (nodeKeys.contains(failedNodeKey)) {
		fail("failed node key not removed: " + failedNodeKey);
	    }
	    
	} finally {
	    for (DummyClient client : dummyClients.values()) {
		try {
		    client.disconnect();
		} catch (Exception e) {
		    // ignore
		}
	    }
	}
    }

    // -- test ClientSession --

    public void testClientSessionIsConnected() throws Exception {
	DummyClient client = createDummyClient("clientname");
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
            txnScheduler.runTask(new TestAbstractKernelRunnable() {
                public void run() {
                    DummyAppListener appListener = getAppListener();
                    Set<ClientSession> sessions = appListener.getSessions();
                    if (sessions.isEmpty()) {
                        fail("appListener contains no client sessions!");
                    }
                    for (ClientSession session : appListener.getSessions()) {
                        if (session.isConnected() == true) {
                            System.err.println("session is connected");
                            return;
                        } else {
                            fail("Expected connected session: " + session);
                        }
                    }
                    fail("expected a connected session");
                }
            }, taskOwner);
	} finally {
	    client.disconnect();
	}
    }

    public void testClientSessionGetName() throws Exception {
	final String name = "clientname";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
            txnScheduler.runTask(new TestAbstractKernelRunnable() {
                public void run() {
                    DummyAppListener appListener = getAppListener();
                    Set<ClientSession> sessions = appListener.getSessions();
                    if (sessions.isEmpty()) {
                        fail("appListener contains no client sessions!");
                    }
                    for (ClientSession session : appListener.getSessions()) {
                        if (session.getName().equals(name)) {
                            System.err.println("names match");
                            return;
                        } else {
                            fail("Expected session name: " + name +
                                 ", got: " + session.getName());
                        }
                    }
                    fail("expected disconnected session");
                }
             }, taskOwner);
	} finally {
	    client.disconnect();
	}
    }

    public void testClientSessionToString() throws Exception {
	final String name = "testClient";
	DummyClient client = createDummyClient(name);
	try {
	    assertTrue(client.connect(serverNode.getAppPort()).login());
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		    public void run() {
			ClientSession session = (ClientSession)
			    dataService.getBinding(name);
			if (!(session instanceof ClientSessionWrapper)) {
			    fail("session not instance of " +
				 "ClientSessionWrapper");
			}
			System.err.println("session: " + session);
		    }
		}, taskOwner);
	} finally {
	    client.disconnect();
	}
    }
    
    public void testClientSessionToStringNoTransaction() throws Exception {
	final String name = "testClient";
	DummyClient client = createDummyClient(name);
	try {
	    assertTrue(client.connect(serverNode.getAppPort()).login());
	    GetClientSessionTask task = new GetClientSessionTask(name);
	    txnScheduler.runTask(task, taskOwner);
	    try {
		System.err.println("session: " + task.session.toString());
		return;
	    } catch (Exception e) {
		e.printStackTrace();
		fail("unexpected exception in ClientSessionWrapper.toString");
	    }
	} finally {
	    client.disconnect();
	}
    }

    private class GetClientSessionTask extends TestAbstractKernelRunnable {
	private final String name;
	volatile ClientSession session;

	GetClientSessionTask(String name) {
	    this.name = name;
	}

	public void run() {
	    session = (ClientSession) dataService.getBinding(name);
	    if (!(session instanceof ClientSessionWrapper)) {
		fail("session not instance of ClientSessionWrapper");
	    }
	}
    }
	
    public void testClientSessionSendUnreliableMessages() throws Exception {
	DummyClient client = createDummyClient("dummy");
	int iterations = 3;
	int numAdditionalNodes = 2;
	Queue<byte[]> messages =
	    sendMessagesFromNodesToClient(
 		client, numAdditionalNodes, iterations, Delivery.UNRELIABLE,
		false);
	int expectedMessages = (1 + numAdditionalNodes) * iterations;
	assertEquals(expectedMessages, messages.size());
    }

    public void testClientSessionSendUnreliableMessagesWithFailure()
	throws Exception
    {
	DummyClient client = createDummyClient("dummy");
	int iterations = 3;
	int numAdditionalNodes = 2;
	Queue<byte[]> messages =
	    sendMessagesFromNodesToClient(
		client, numAdditionalNodes, iterations, Delivery.UNRELIABLE,
		true);
	int expectedMessages = iterations;
	assertEquals(expectedMessages, messages.size());
    }
    
    public void testClientSessionSendSequence() throws Exception {
	DummyClient client = createDummyClient("dummy");
	int iterations = 3;
	int numAdditionalNodes = 2;
	Queue<byte[]> messages =
	    sendMessagesFromNodesToClient(
		client, numAdditionalNodes, iterations, Delivery.RELIABLE,
		false);
	int expectedMessages = (1 + numAdditionalNodes) * iterations;
	client.validateMessageSequence(messages, expectedMessages);
    }
    
    private Queue<byte[]> sendMessagesFromNodesToClient(
	    final DummyClient client, int numAdditionalNodes, int iterations,
	    final Delivery delivery, final boolean oneUnreliableServer)
	throws Exception
    {
	try {
	    final String counterName = "counter";
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    for (int i = 0; i < numAdditionalNodes; i++) {
		addNodes(Integer.toString(i));
	    }
	    
	    final List<SgsTestNode> nodes = new ArrayList<SgsTestNode>();
	    nodes.add(serverNode);
	    nodes.addAll(additionalNodes.values());
	    int expectedMessages = 
		oneUnreliableServer ?
		iterations :
		nodes.size() * iterations;
	    
	    // Replace each node's ClientSessionServer, bound in the data
	    // service, with a wrapped server that delays before sending
	    // the message.
	    final DataService ds = dataService;
	    TransactionScheduler txnScheduler =
		serverNode.getSystemRegistry().
		    getComponent(TransactionScheduler.class);
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		@SuppressWarnings("unchecked")
		public void run() {
		    boolean setUnreliableServer = oneUnreliableServer;
		    for (SgsTestNode node : nodes) {
			String key = "com.sun.sgs.impl.service.session.server." +
			    node.getNodeId();
			ClientSessionServer sessionServer =
			    ((ManagedSerializable<ClientSessionServer>)
			     dataService.getServiceBinding(key)).get();
			InvocationHandler handler;
			if (setUnreliableServer) {
			    handler = new HungryInvocationHandler(sessionServer);
			    setUnreliableServer = false;
			} else {
			    handler =
				new DelayingInvocationHandler(sessionServer);
			}
			ClientSessionServer delayingServer =
			    (ClientSessionServer)
			    Proxy.newProxyInstance(
				ClientSessionServer.class.getClassLoader(),
				new Class[] { ClientSessionServer.class },
				handler);
			dataService.setServiceBinding(
			    key, new ManagedSerializable(delayingServer));
		    }
		}}, taskOwner);

	    for (int i = 0; i < iterations; i++) {
		for (SgsTestNode node : nodes) {
		    TransactionScheduler localTxnScheduler = 
			node.getSystemRegistry().
			    getComponent(TransactionScheduler.class);
		    Identity identity = node.getProxy().getCurrentOwner();
		    localTxnScheduler.scheduleTask(
		    	  new TestAbstractKernelRunnable() {
			    public void run() {
				DataManager dataManager =
				    AppContext.getDataManager();
				Counter counter;
				try {
				    counter = (Counter)
					dataManager.getBinding(counterName);
				} catch (NameNotBoundException e) {
				    throw new MaybeRetryException("retry", true);
				}
				ClientSession session = (ClientSession)
				    dataManager.getBinding(client.name);
				MessageBuffer buf = new MessageBuffer(4);
				buf.putInt(counter.getAndIncrement());
				session.send(ByteBuffer.wrap(buf.getBuffer()),
					     delivery);
			    }},
			
			identity);
		}
	    }
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    AppContext.getDataManager().
			setBinding(counterName, new Counter());
		}}, taskOwner);

	    return client.waitForClientToReceiveExpectedMessages(
		expectedMessages);

	} finally {
	    client.disconnect();
	}
    }

    public void testClientSessionSendNullMessage() throws Exception {
	try {
	    sendBufferToClient(null, null);
	    fail("Expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }
    
    public void testClientSessionSendNullDelivery() throws Exception {
	try {
	    sendBufferToClient(ByteBuffer.wrap(new byte[0]), "", null);
	    fail("Expected NullPointerException");
	} catch (NullPointerException e) {
	    System.err.println(e);
	}
    }
    
    public void testClientSessionSendSameBuffer() throws Exception {
	String msgString = "buffer";
	MessageBuffer msg =
	    new MessageBuffer(MessageBuffer.getSize(msgString));
	msg.putString(msgString);
	ByteBuffer buf = ByteBuffer.wrap(msg.getBuffer());
	sendBufferToClient(buf, msgString);
    }

    public void testClientSessionSendSameBufferWithOffset()
	throws Exception
    {
	String msgString = "offset buffer";
	MessageBuffer msg =
	    new MessageBuffer(MessageBuffer.getSize(msgString) + 1);
	msg.putByte(0);
	msg.putString(msgString);
	ByteBuffer buf = ByteBuffer.wrap(msg.getBuffer());
	buf.position(1);
	sendBufferToClient(buf, msgString);
    }

    private void sendBufferToClient(final ByteBuffer buf,
				    final String expectedMsgString)
	throws Exception
    {
	sendBufferToClient(buf, expectedMsgString, Delivery.RELIABLE);
    }
	
    private void sendBufferToClient(final ByteBuffer buf,
				    final String expectedMsgString,
				    final Delivery delivery)
	throws Exception
    {	
	final String name = "dummy";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    final int numMessages = 3;
	    for (int i = 0; i < numMessages; i++) {
		txnScheduler.runTask(new TestAbstractKernelRunnable() {
			public void run() {
			    ClientSession session = (ClientSession)
				AppContext.getDataManager().getBinding(name);
			    System.err.println("Sending messages");
			    session.send(buf, delivery);
			} }, taskOwner);
	    }
	
	    System.err.println("waiting for client to receive messages");
	    Queue<byte[]> messages =
		client.waitForClientToReceiveExpectedMessages(numMessages);
	    for (byte[] message : messages) {
		if (message.length == 0) {
		    fail("message buffer emtpy");
		}
		String msgString = (new MessageBuffer(message)).getString();
		if (!msgString.equals(expectedMsgString)) {
		    fail("expected: " + expectedMsgString + ", received: " +
			 msgString);
		} else {
		    System.err.println("received expected message: " +
				       msgString);
		}
	    }
	} finally {
	    client.disconnect();
	}
    }
    
    private static class Counter implements ManagedObject, Serializable {
	private static final long serialVersionUID = 1L;

	private int value = 0;

	int getAndIncrement() {
	    AppContext.getDataManager().markForUpdate(this);
	    return value++;
	}
    }

    // Test sending from the server to the client session in a transaction that
    // aborts with a retryable exception to make sure that message buffers are
    // reclaimed.  Try sending 4K bytes, and have the task abort 100 times with
    // a retryable exception so the task is retried.  If the buffers are not
    // being reclaimed then the sends will eventually fail because the buffer
    // space is used up.  Note that this test assumes that sending 400 KB of
    // data will surpass the I/O throttling limit.
    public void testClientSessionSendAbortRetryable() throws Exception {
	DummyClient client = createDummyClient("clientname");
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    txnScheduler.runTask(
		new TestAbstractKernelRunnable() {
		    int tryCount = 0;
		    public void run() {
			Set<ClientSession> sessions =
			    getAppListener().getSessions();
			ClientSession session = sessions.iterator().next();
			try {
			    session.send(ByteBuffer.wrap(new byte[4096]));
			} catch (MessageRejectedException e) {
			    fail("Should not run out of buffer space: " + e);
			}
			if (++tryCount < 100) {
			    throw new MaybeRetryException("Retryable",  true);
			}
		    }
		}, taskOwner);
	} finally {
	    client.disconnect();
	}
    }

    public void testClientSend() throws Exception {
	sendMessagesAndCheck(5, 5, null);
    }

    public void testClientSendWithListenerThrowingRetryableException()
	throws Exception
    {
	sendMessagesAndCheck(
	    5, 5, new MaybeRetryException("retryable", true));
    }

    public void testClientSendWithListenerThrowingNonRetryableException()
	throws Exception
    {
	sendMessagesAndCheck(
	    5, 4, new MaybeRetryException("non-retryable", false));
    }


    public void testLocalSendPerformance() throws Exception {
	final String user = "dummy";
	DummyClient client = (createDummyClient(user)).connect(serverNode.getAppPort());
	assertTrue(client.login());

	isPerformanceTest = true;
	int numIterations = 10;
	final ByteBuffer msg = ByteBuffer.allocate(0);
	long startTime = System.currentTimeMillis();
	for (int i = 0; i < numIterations; i++) {
	    txnScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    DataManager dataManager = AppContext.getDataManager();
		    ClientSession session = (ClientSession)
			dataManager.getBinding(user);
		    session.send(msg);
		}}, taskOwner);
	}
	long endTime = System.currentTimeMillis();
	System.err.println("send, iterations: " + numIterations +
			   ", elapsed time: " + (endTime - startTime) +
			   " ms.");
    }

    public void testRemoveSessionWhileSessionDisconnects() throws Exception {
	final String user = "foo";
	DummyClient client = createDummyClient(user);
	assertTrue(client.connect(serverNode.getAppPort()).login());
	client.sendMessage(new byte[0]);
	client.logout();
	client.checkDisconnectedCallback(true);
    }

    public void testClientSessionReceiveRelocateNotification() throws Exception {
	DummyClient client = createClientToRelocate("newNode");
	client.disconnect();
    }
    
    public void testRelocateClientSession() throws Exception {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	int objectCount = getObjectCount();
	try {
	    SgsTestNode newNode = additionalNodes.get(newNodeHost);
	    client.relocate(newNode.getAppPort(), true, true);
	    // need to wait here to get the true object count to make sure
	    // that objects and bindings aren't cleaned up.
	    Thread.sleep(WAIT_TIME);
	    assertEquals(objectCount, getObjectCount());
	    checkBindings(1);
	    client.assertDisconnectedCallbackNotInvoked();
	} finally {
	    client.disconnect();
	}
    }

    public void testRelocateClientSessionSendingAfterRelocate()
	throws Exception
    {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	try {
	    SgsTestNode newNode = additionalNodes.get(newNodeHost);
	    client.relocate(newNode.getAppPort(), true, true);
	    sendMessagesFromNodeToClient(serverNode, client, 4, 0);
	    sendMessagesFromNodeToClient(newNode, client, 4, 10);
	} finally {
	    client.disconnect();
	}
    }

    public void testRelocateClientSessionSendingDuringRelocate()
	throws Exception
    {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	try {
	    SgsTestNode newNode = additionalNodes.get(newNodeHost);
	    int objectCount = getObjectCount();
	    sendMessagesFromNode(serverNode, client, 4, 0);
	    sendMessagesFromNode(newNode, client, 4, 10);
	    client.relocate(newNode.getAppPort(), true, true);
	    waitForClientMessages(client, 4, 0);
	    waitForClientMessages(client, 4, 10);
	    waitForExpectedObjectCount(objectCount);
	} finally {
	    client.disconnect();
	}
    }
    
    public void testRelocateInvalidRelocationKey()  throws Exception {
	String newNodeHost = "new";
	DummyClient client = createClientToRelocate(newNodeHost);
	try {
	    SgsTestNode newNode = additionalNodes.get(newNodeHost);
	    int objectCount = getObjectCount();
	    client.relocate(newNode.getAppPort(), false, false);
	    waitForExpectedObjectCount(
		objectCount - MANAGED_OBJECTS_PER_SESSION);
	    checkBindings(0);
	    client.assertDisconnectedCallbackInvoked(false);
	} finally {
	    client.disconnect();
	}
    }

    public void testRelocateWithInterveningClientLoginRejected()
	throws Exception
    {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	DummyClient otherClient = createDummyClient("foo");
	SgsTestNode newNode = additionalNodes.get(newNodeHost);
	try {
	    int newPort = additionalNodes.get(newNodeHost).getAppPort();
	    assertFalse(otherClient.connect(newPort).login());
	    client.relocate(newPort, true, true);
	    checkBindings(1);
	    client.assertDisconnectedCallbackNotInvoked();
	} finally {
	    client.disconnect();
	}
    }

    public void testRelocateWithLoginPreemptedAfterRelocate()
	throws Exception
    {
	String newNodeHost = "newNode";
	allowNewLogin = true;	// enable login preemption on new node
	DummyClient client = createClientToRelocate(newNodeHost);
	DummyClient otherClient = createDummyClient("foo");
	SgsTestNode newNode = additionalNodes.get(newNodeHost);
	try {
	    int newPort = additionalNodes.get(newNodeHost).getAppPort();
	    client.relocate(newPort, true, true);
	    int objectCount = getObjectCount();
	    assertTrue(otherClient.connect(newPort).login());
	    //waitForExpectedObjectCount(objectCount);
	    checkBindings(1);
	    client.assertDisconnectedCallbackInvoked(false);
	} finally {
	    client.disconnect();
	}
    }

    public void testRelocateWithRedirect()
	throws Exception
    {
	String host2 = "host2";
	String host3 = "host3";
	DummyClient client = createClientToRelocate(host2);
	addNodes(host3);
	SgsTestNode node2 = additionalNodes.get(host2);
	SgsTestNode node3 = additionalNodes.get(host3);
	DirectiveNodeAssignmentPolicy.instance.
	    moveIdentity("foo", node2.getNodeId(),
			 node3.getNodeId());
	int objectCount = getObjectCount();
	client.relocate(node2.getAppPort(), true, false);
	waitForExpectedObjectCount(
	    objectCount - MANAGED_OBJECTS_PER_SESSION);
	checkBindings(0);
	client.assertDisconnectedCallbackInvoked(false);
    }

    public void testOldNodeFailsDuringRelocateToNewNode()
	throws Exception
    {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	int objectCount = getObjectCount();
	checkBindings(1);
	try {
	    // Simulate oldNode crashing by having client not connect to
	    // newNode by the timeout period.
	    Thread.sleep(WAIT_TIME);
	    assertEquals(objectCount - MANAGED_OBJECTS_PER_SESSION,
			 getObjectCount());
	    checkBindings(0);
	    client.assertDisconnectedCallbackInvoked(false);
	} finally {
	    client.disconnect();
	}
    }

    public void testNewNodeFailsDuringRelocateToNewNode()
	throws Exception
    {
	String newNodeHost = "newNode";
	DummyClient client = createClientToRelocate(newNodeHost);
	checkBindings(1);
	try {
	    // Shutdown new node, and wait before checking if
	    // session's persistent data has been cleaned up.
	    SgsTestNode newNode = additionalNodes.get(newNodeHost);
	    newNode.shutdown(false);
	    Thread.sleep(WAIT_TIME);
	    checkBindings(0);
	    client.assertDisconnectedCallbackInvoked(false);
	} finally {
	    client.disconnect();
	}
    }
    
    /**
     * Performs the following in preparation for a relocation test:
     * <ul>
     * <li>creates a new {@code DummyClient} named "foo"
     * <li>creates a new node named {@code newNodeHost} 
     * <li>logs the client into the server node
     * <li>reassigns the client's identity to the new node
     * <li>waits for the client to receive the relocate notification
     * <li>returns the constructed {@code DummyClient}
     * </ul>
     */
    private DummyClient createClientToRelocate(String newNodeHost)
	throws Exception
    {
	final String name = "foo";
	DirectiveNodeAssignmentPolicy.instance.setRoundRobin(false);
	addNodes(newNodeHost);
	DummyClient client = createDummyClient(name);
	assertTrue(client.connect(serverNode.getAppPort()).login());
	SgsTestNode newNode = additionalNodes.get(newNodeHost);
	System.err.println("reassigning identity:" + name +
			   " from server node to host: " +
			   newNodeHost);
	DirectiveNodeAssignmentPolicy.instance.
	    moveIdentity(name, serverNode.getNodeId(), newNode.getNodeId());
	System.err.println("(done) reassigning identity");
	client.waitForRelocationNotification(newNode.getAppPort());
	return client;
    }

    /**
     * Sends the number of specified messages from the specified node to
     * the specified client.  The content of each message is a consecutive
     * integer starting at the specified offset.
     */
    private void sendMessagesFromNode(
	SgsTestNode node, final DummyClient client, final int numMessages,
	final int offset)
	throws Exception
    {
	System.err.println("sending messages to client [" + client.name + "]");
        TransactionScheduler transactionScheduler = 
            node.getSystemRegistry(). getComponent(TransactionScheduler.class);
	for (int i = 0; i < numMessages; i++) {
	    final int x = i + offset;
	    transactionScheduler.runTask(new TestAbstractKernelRunnable() {
		public void run() {
		    ClientSession session = (ClientSession)
			AppContext.getDataManager().getBinding(client.name);
			ByteBuffer buf = ByteBuffer.allocate(4);
			buf.putInt(x).flip();
			session.send(buf, Delivery.RELIABLE);
		    } }, taskOwner);
	}
    }

    /**
     * Waits for the client to receive the specified number of messages and
     * verifies the message content.  The content of each message is a
     * consecutive integer starting at the specified offset.
     */
    private void waitForClientMessages(
	 DummyClient client, int numMessages, int offset)
	throws Exception
    {
	System.err.println("waiting for client [" + client.name +
			   "] to receive messages");
	Queue<byte[]> messages =
	    client.waitForClientToReceiveExpectedMessages(numMessages);
	int expected = offset;
	for (byte[] message : messages) {
	    if (message.length != 4) {
		fail("message buffer emtpy");
	    }
	    int x = ByteBuffer.wrap(message).getInt();
	    if (x != expected) {
		fail("expected: " + expected + ", received: " + x);
	    } else {
		System.err.println("received expected message: " + x);
	    }
	    expected++;
	}
	messages.clear();
    }
    
    /**
     * Sends the number of specified messages from the specified node to
     * the specified client and waits for the client to receive the
     * expected messages.  The content of each message is a consecutive
     * integer starting at the specified offset.
     */
    private void sendMessagesFromNodeToClient(
	SgsTestNode node,  DummyClient client, int numMessages, int offset)
	throws Exception
    {
        sendMessagesFromNode(node, client, numMessages, offset);
	waitForClientMessages(client, numMessages, offset);
    }

    private void waitForExpectedObjectCount(int expectedCount)
	throws Exception
    {
	int objectCount = 0;
	for (int i = 0; i < 10; i++) {
	    objectCount = getObjectCount();
	    if (objectCount == expectedCount) {
		return;
	    } else {
		Thread.sleep(WAIT_TIME / 10);
	    }
	}
	assertEquals(expectedCount, objectCount);
    }

    /* -- other methods -- */

    /**
     * If {@code throwException} is non-null, the {@code
     * ClientSessionListener} will throw the specified exception in its
     * {@code receivedMessage} method for only the first message it
     * receives.
     */
    private void sendMessagesAndCheck(
	int numMessages, int expectedMessages,
	RuntimeException throwException)
	throws Exception
    {
	String name = "clientname";
	DummyClient client = createDummyClient(name);
	try {
	    client.connect(serverNode.getAppPort());
	    assertTrue(client.login());
	    receivedMessageException = throwException;
	    client.sendMessagesInSequence(numMessages, expectedMessages);
	} finally {
	    client.disconnect();
	}
    }

    /**
     * Check that the session bindings are the expected number and throw an
     * exception if they aren't.
     */
    private void checkBindings(int numExpected) throws Exception {
	
	List<String> listenerKeys = getServiceBindingKeys(LISTENER_PREFIX);
	System.err.println("Listener keys: " + listenerKeys);
	if (listenerKeys.size() != numExpected) {
	    fail("expected " + numExpected + " listener keys, got " +
		 listenerKeys.size());
	}
	    
	List<String> sessionKeys = getServiceBindingKeys(SESSION_PREFIX);
	System.err.println("Session keys: " + sessionKeys);
	if (sessionKeys.size() != numExpected) {
	    fail("expected " + numExpected + " session keys, got " +
		 sessionKeys.size());
	}
	    
	List<String> sessionNodeKeys =
	    getServiceBindingKeys(SESSION_NODE_PREFIX);
	System.err.println("Session node keys: " + sessionNodeKeys);
	if (sessionNodeKeys.size() != numExpected) {
	    fail("expected " + numExpected + " session node keys, got " +
		 sessionNodeKeys.size());
	}
    }
    
    private List<String> getServiceBindingKeys(String prefix) throws Exception {
        GetKeysTask task = new GetKeysTask(prefix);
        txnScheduler.runTask(task, taskOwner);
        return task.getKeys();
    }

    private class GetKeysTask extends TestAbstractKernelRunnable {
        private List<String> keys = new ArrayList<String>();
        private final String prefix;
        GetKeysTask(String prefix) {
            this.prefix = prefix;
        }
        public void run() throws Exception {
            String key = prefix;
            for (;;) {
                key = dataService.nextServiceBoundName(key);
                if (key == null ||
                    ! key.regionMatches(
                          0, prefix, 0, prefix.length()))
                {
                    break;
                }
                keys.add(key);
            }
        }
        public List<String> getKeys() { return keys;}
    }

    private void printIt(String line) {
	if (! isPerformanceTest) {
	    System.err.println(line);
	}
    }
    
    /** Find the app listener */
    private DummyAppListener getAppListener() {
	return (DummyAppListener) dataService.getServiceBinding(
	    StandardProperties.APP_LISTENER);
    }


    public static class DummyAppListener implements AppListener, 
                                                    ManagedObject,
                                                    Serializable {

	private final static long serialVersionUID = 1L;

	private final Map<ManagedReference<ClientSession>,
			  ManagedReference<DummyClientSessionListener>>
	    sessions = Collections.synchronizedMap(
		new HashMap<ManagedReference<ClientSession>,
			    ManagedReference<DummyClientSessionListener>>());

        /** {@inheritDoc} */
	public ClientSessionListener loggedIn(ClientSession session) {

	    if (!(session instanceof ClientSessionWrapper)) {
		throw new IllegalArgumentException(
		    "session not instance of ClientSessionWrapper:" +
		    session);
	    }

	    String name = session.getName();
	    DummyClientSessionListener listener;
	    
	    if (name.equals(RETURN_NULL)) {
		return null;
	    } else if (name.equals(NON_SERIALIZABLE)) {
		return new NonSerializableClientSessionListener();
	    } else if (name.equals(THROW_RUNTIME_EXCEPTION)) {
		throw new RuntimeException("loggedIn throwing an exception");
	    } else if (name.equals(DISCONNECT_THROWS_NONRETRYABLE_EXCEPTION) ||
		       name.startsWith("badClient")) {
		listener = new DummyClientSessionListener(name, session, true);
	    } else {
		listener = new DummyClientSessionListener(name, session, false);
	    }
	    DataManager dataManager = AppContext.getDataManager();
	    ManagedReference<ClientSession> sessionRef =
		dataManager.createReference(session);
	    ManagedReference<DummyClientSessionListener> listenerRef =
		dataManager.createReference(listener);
	    dataManager.markForUpdate(this);
	    sessions.put(sessionRef, listenerRef);
	    dataManager.setBinding(session.getName(), session);
	    System.err.println("DummyAppListener.loggedIn: session:" + session);
	    return listener;
	}

        /** {@inheritDoc} */
	public void initialize(Properties props) {
	}

	private Set<ClientSession> getSessions() {
	    Set<ClientSession> sessionSet =
		new HashSet<ClientSession>();
	    for (ManagedReference<ClientSession> sessionRef
		     : sessions.keySet())
	    {
		sessionSet.add(sessionRef.get());
	    }
	    return sessionSet;
	}
    }

    private static class NonSerializableClientSessionListener
	implements ClientSessionListener
    {
        /** {@inheritDoc} */
	public void disconnected(boolean graceful) {
	}

        /** {@inheritDoc} */
	public void receivedMessage(ByteBuffer message) {
	}
    }

    private static class DummyClientSessionListener
	implements ClientSessionListener, Serializable, ManagedObject
    {
	private final static long serialVersionUID = 1L;
	private final String name;
	private final ManagedReference<ClientSession> sessionRef;
	private BigInteger reconnectKey = null;
	private final boolean disconnectedThrowsException;


	DummyClientSessionListener(
	    String name, ClientSession session,
	    boolean disconnectedThrowsException)
	{
	    this.name = name;
	    this.sessionRef =
		AppContext.getDataManager().createReference(session);
	    this.disconnectedThrowsException = disconnectedThrowsException;
	}

        /** {@inheritDoc} */
	public void disconnected(boolean graceful) {
	    System.err.println("DummyClientSessionListener[" + name +
			       "] disconnected invoked with " + graceful);
	    AppContext.getDataManager().removeObject(sessionRef.get());
	    DummyClient client =
                    reconnectKey == null ? null :
                                           dummyClients.get(reconnectKey);
	    if (client != null) {
		client.setDisconnectedCallbackInvoked(graceful);
	    }
	    if (disconnectedThrowsException) {
		throw new RuntimeException(
		    "disconnected throws non-retryable exception");
	    }
	}

        /** {@inheritDoc} */
	public void receivedMessage(ByteBuffer message) {
            byte[] messageBytes = new byte[message.remaining()];
	    message.get(messageBytes);
	    MessageBuffer buf = new MessageBuffer(messageBytes);
	    AppContext.getDataManager().markForUpdate(this);
	    reconnectKey = new BigInteger(1, buf.getByteArray());
	    byte[] bytes = buf.getByteArray();
	    if (bytes.length == 0) {
		return;
	    }
	    DummyClient client = dummyClients.get(reconnectKey);
	    System.err.println(
		"receivedMessage: " + HexDumper.toHexString(bytes) + 
		"\nthrow exception: " + receivedMessageException);
	    if (receivedMessageException != null) {
		RuntimeException re = receivedMessageException;
		receivedMessageException = null;
		throw re;
	    } else {
		client.messages.add(bytes);
	    }
	    synchronized (client.messages) {
		if (client.messages.size() == client.expectedMessages0) {
		    client.messages.notifyAll();
		}
	    }
	}
    }

    private static class MaybeRetryException
	extends RuntimeException implements ExceptionRetryStatus
    {
	private static final long serialVersionUID = 1L;
	private boolean retry;

	public MaybeRetryException(String s, boolean retry) {
	    super(s);
	    this.retry = retry;
	}

	public boolean shouldRetry() {
	    return retry;
	}
    }

    /**
     * This invocation handler adds a 100 ms delay before invoking any
     * method on the underlying instance.
     */
    private static class DelayingInvocationHandler
	implements InvocationHandler, Serializable
    {
	private final static long serialVersionUID = 1L;
	private Object obj;
	
	DelayingInvocationHandler(Object obj) {
	    this.obj = obj;
	}
	
	public Object invoke(Object proxy, Method method, Object[] args)
	    throws Exception
	{
	    Thread.sleep(100);
	    try {
		return method.invoke(obj, args);
	    } catch (InvocationTargetException e) {
		Throwable cause = e.getCause();
		if (cause instanceof Exception) {
		    throw (Exception) cause;
		} else if (cause instanceof Error) {
		    throw (Error) cause;
		} else {
		    throw new RuntimeException(
			"Unexpected exception:" + cause, cause);
		}
	    }
	}
    }

    /**
     * This invocation handler prevents forwarding the {@code send} and
     * {@code serviceEventQueue} methods to the underlying instance.
     */
    private static class HungryInvocationHandler
	implements InvocationHandler, Serializable
    {
	private final static long serialVersionUID = 1L;
	private Object obj;
	
	HungryInvocationHandler(Object obj) {
	    this.obj = obj;
	}
	
	public Object invoke(Object proxy, Method method, Object[] args)
	    throws Exception
	{
	    String name = method.getName();
	    if (name.equals("send") || name.equals("serviceEventQueue")) {
		return null;
	    }
	    try {
		return method.invoke(obj, args);
	    } catch (InvocationTargetException e) {
		Throwable cause = e.getCause();
		if (cause instanceof Exception) {
		    throw (Exception) cause;
		} else if (cause instanceof Error) {
		    throw (Error) cause;
		} else {
		    throw new RuntimeException(
			"Unexpected exception:" + cause, cause);
		}
	    }
	}
    }

    private int getObjectCount() throws Exception {
	GetObjectCountTask task = new GetObjectCountTask();
	txnScheduler.runTask(task, taskOwner);
	return task.count;
    }
    
    private class GetObjectCountTask extends TestAbstractKernelRunnable {

	volatile int count = 0;
	
	GetObjectCountTask() {
	}

	public void run() {
	    count = 0;
	    BigInteger last = null;
	    while (true) {
		BigInteger next = dataService.nextObjectId(last);
		if (next == null) {
		    break;
		}
                // NOTE: this count is used at the end of the test to make sure
                // that no objects were leaked in stressing the structure but
                // any given service (e.g., the task service) may accumulate
                // managed objects, so a more general way to exclude these from
                // the count would be nice but for now the specific types that
                // are accumulated get excluded from the count.
		ManagedReference ref =
		    dataService.createReferenceForId(next);
		Object obj = ref.get();
                String name = obj.getClass().getName();
                if (! name.equals("com.sun.sgs.impl.service.task.PendingTask") &&
		    ! name.equals("com.sun.sgs.impl.service.nodemap.IdentityMO"))
		{
		    /*
		    System.err.print(count + "[" + obj.getClass().getName() + "]:");
		    try {
			System.err.println(obj.toString());
		    } catch (ObjectNotFoundException e) {
			System.err.println("<< caught ObjectNotFoundException >>");
		    }
		    */
                    count++;
		}
                last = next;
	    }
	}
    }

    private static class DummyStatusListener
	implements ClientSessionStatusListener
    {
	public void disconnected(BigInteger sessionRefId) { }

	public void prepareToRelocate(BigInteger sessionRefId, long newNode,
				      SimpleCompletionHandler handler)
	{ }
    }

    private DummyClient createDummyClient(String name) {
	return new DummyClient(name, dummyClients);
    }
}
